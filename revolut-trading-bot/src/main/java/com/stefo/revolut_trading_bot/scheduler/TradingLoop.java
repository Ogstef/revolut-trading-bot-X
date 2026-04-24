package com.stefo.revolut_trading_bot.scheduler;

import com.stefo.revolut_trading_bot.alert.AlertService;
import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.execution.LeveragedPaperTradingService;
import com.stefo.revolut_trading_bot.execution.OrderExecutionService;
import com.stefo.revolut_trading_bot.market.MarketDataClient;
import com.stefo.revolut_trading_bot.market.MarketDataService;
import com.stefo.revolut_trading_bot.model.dto.BalanceResponse;
import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.model.enums.TradingVehicle;
import com.stefo.revolut_trading_bot.portfolio.VehicleBalanceService;
import com.stefo.revolut_trading_bot.repository.PositionRepository;
import com.stefo.revolut_trading_bot.risk.RiskManager;
import com.stefo.revolut_trading_bot.strategy.Signal;
import com.stefo.revolut_trading_bot.strategy.SignalEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Main trading heartbeat — runs every N seconds (configured via trading.polling-interval-seconds).
 *
 * From Phase 8, the cycle structure is:
 *   1. Evaluate signals for ALL pairs × ALL strategies (candles fetched once per pair)
 *   2. Group signals by pair
 *   3. For each pair: for each strategy signal → monitor TP/SL → execute signal
 *
 * Each (pair, strategy) combination has its own isolated circuit breakers, balance, and positions.
 * A bad cycle for BTC-EUR/EMA_CROSSOVER never blocks ETH-EUR/MACD.
 *
 * All exceptions are caught and logged — a bad cycle never stops future cycles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingLoop {

    private static final String MODE_PAPER = "PAPER";

    private final SignalEngine                 signalEngine;
    private final OrderExecutionService        orderExecutionService;
    private final LeveragedPaperTradingService leveragedPaperTradingService;
    private final PositionRepository           positionRepository;
    private final RiskManager                  riskManager;
    private final MarketDataService            marketDataService;
    private final MarketDataClient             marketDataClient;
    private final TradingConfig                tradingConfig;
    private final BotStateService              botStateService;
    private final AlertService                 alertService;
    private final VehicleBalanceService        vehicleBalanceService;

    @Scheduled(fixedDelayString = "#{${trading.polling-interval-seconds:30} * 1000}")
    public void run() {
        if (!botStateService.isActive()) {
            log.info("Bot is stopped (emergency stop engaged) — skipping this cycle");
            return;
        }
        try {
            runCycle();
        } catch (Exception e) {
            log.error("Trading cycle failed — resuming on next tick", e);
            alertService.tradingCycleFailed(e);
        }
    }

    // ─── Private ─────────────────────────────────────────────────────────────

    private void runCycle() {
        log.info("══════════ Trading cycle start ══════════");

        // Step 1: fetch candles ONCE per (pair, interval) — all strategies share the same BarSeries
        List<Signal> allSignals = signalEngine.evaluateAllPairsAndPersist();
        log.info("Evaluated {} strategies across {} pairs x {} intervals — {} total signals",
                signalEngine.registeredStrategies().size(),
                tradingConfig.getPairs().size(),
                tradingConfig.getIntervals().size(),
                allSignals.size());

        // Step 2: per-cycle SPOT risk snapshot — 3 aggregate queries replace ~3 queries per signal
        RiskManager.CycleSnapshot riskSnapshot = riskManager.snapshotAll();

        // Step 3: accrue funding on ALL open leveraged positions once per cycle
        accrueFundingOnAllLeveraged();

        // Step 4: group signals by (pair, interval)
        Map<String, Map<String, List<Signal>>> signalsByPairAndInterval = allSignals.stream()
                .collect(Collectors.groupingBy(Signal::pair,
                        Collectors.groupingBy(Signal::interval)));

        // Step 5: per-(pair, interval, strategy) execution — fires SPOT always, then each active
        // leveraged vehicle if leverage is enabled and the (pair, interval) is in the leveraged subset.
        List<TradingVehicle> leveragedVehicles = activeLeveragedVehicles();
        signalsByPairAndInterval.forEach((pair, byInterval) -> {
            BigDecimal currentPrice = marketDataService.getCurrentPriceForPair(pair);
            log.info("[{}] Price: {}", pair, currentPrice);
            byInterval.forEach((interval, signals) -> {
                log.debug("[{}][{}] Processing {} signals", pair, interval, signals.size());
                signals.forEach(signal -> {
                    runSpotExecution(signal, currentPrice, riskSnapshot);
                    if (isLeveragedTarget(pair, interval)) {
                        for (TradingVehicle vehicle : leveragedVehicles) {
                            runLeveragedExecution(signal, vehicle, currentPrice);
                        }
                    }
                });
            });
        });

        log.info("══════════ Trading cycle end ══════════");
    }

    private void runSpotExecution(Signal signal, BigDecimal currentPrice,
                                  RiskManager.CycleSnapshot riskSnapshot) {
        String pair              = signal.pair();
        String interval          = signal.interval();
        StrategyType strategyName = signal.strategyType();
        log.info("[{}][{}][{}] Signal: {} | reason: {}", pair, interval, strategyName, signal.type(), signal.reason());

        // Monitor TP/SL only for this (pair, interval, strategy, SPOT)'s open positions
        orderExecutionService.monitorPositionsForVehicle(currentPrice, pair, interval, strategyName, TradingVehicle.SPOT);

        // Resolve balance — shared per (pair, strategy), NOT split by interval
        BigDecimal balance = resolveBalanceForStrategy(pair, strategyName);
        log.info("[{}][{}][{}] Balance: {} EUR", pair, interval, strategyName, balance);

        // Read risk state from the per-cycle SPOT snapshot — no per-signal DB round-trip
        RiskManager.RiskStatus riskStatus =
                riskManager.statusFromSnapshot(riskSnapshot, balance, pair, interval, strategyName);
        log.info("[{}][{}][{}] Risk — openPositions: {} | dailyPnl: {} | consecutiveLosses: {} | circuitBreaker: {}",
                pair, interval, strategyName, riskStatus.openPositions(), riskStatus.dailyPnl(),
                riskStatus.consecutiveLosses(), riskStatus.anyCircuitBreakerTripped());

        if (riskStatus.anyCircuitBreakerTripped()) {
            log.warn("[{}][{}][{}] ⚠ Circuit breaker active — skipping SPOT trade execution", pair, interval, strategyName);
            alertService.circuitBreakerTripped(pair + "/" + interval + "/" + strategyName
                    + " openPositions=" + riskStatus.openPositions()
                    + " dailyPnl=" + riskStatus.dailyPnl()
                    + " consecutiveLosses=" + riskStatus.consecutiveLosses());
            return;
        }

        orderExecutionService.executeSignalForVehicle(signal, TradingVehicle.SPOT, balance, currentPrice);
    }

    private void runLeveragedExecution(Signal signal, TradingVehicle vehicle, BigDecimal currentPrice) {
        String pair              = signal.pair();
        String interval          = signal.interval();
        StrategyType strategyName = signal.strategyType();

        // Monitor liquidation / TP / SL for this quadruple first
        orderExecutionService.monitorPositionsForVehicle(currentPrice, pair, interval, strategyName, vehicle);

        // Balance-gated; no circuit-breaker for leveraged vehicles in this phase
        BigDecimal available = vehicleBalanceService.available(pair, strategyName, vehicle);
        log.debug("[{}][{}][{}][{}] Leveraged balance: {} EUR", pair, interval, strategyName, vehicle, available);

        orderExecutionService.executeSignalForVehicle(signal, vehicle, available, currentPrice);
    }

    private void accrueFundingOnAllLeveraged() {
        if (!tradingConfig.getLeverage().isEnabled()) return;
        LocalDateTime now = LocalDateTime.now();
        List<Position> openLeveraged = new ArrayList<>();
        for (TradingVehicle v : activeLeveragedVehicles()) {
            openLeveraged.addAll(positionRepository.findByStatusAndVehicle(OrderStatus.OPEN, v));
        }
        if (openLeveraged.isEmpty()) return;
        log.debug("Accruing funding on {} open leveraged position(s)", openLeveraged.size());
        for (Position p : openLeveraged) {
            leveragedPaperTradingService.accrueFunding(p, now);
        }
    }

    private List<TradingVehicle> activeLeveragedVehicles() {
        TradingConfig.Leverage lev = tradingConfig.getLeverage();
        if (!lev.isEnabled()) return List.of();
        return lev.getRatios().stream()
                .map(TradingVehicle::forLeverage)
                .collect(Collectors.toList());
    }

    private boolean isLeveragedTarget(String pair, String interval) {
        TradingConfig.Leverage lev = tradingConfig.getLeverage();
        if (!lev.isEnabled()) return false;
        if (!lev.getPairs().contains(pair)) return false;
        int minutes = TradingConfig.intervalMinutes(interval);
        return lev.getIntervals().contains(minutes);
    }

    /**
     * Resolves the effective balance for a given (pair, strategy):
     *   - LIVE mode → real EUR balance from Revolut API
     *   - PAPER     → balance from trading.strategy-balances[pair][strategy],
     *                 falling back to trading.paper-balance if not configured
     */
    private BigDecimal resolveBalanceForStrategy(String pair, StrategyType strategyType) {
        if (MODE_PAPER.equalsIgnoreCase(tradingConfig.getMode())) {
            Map<String, Map<StrategyType, BigDecimal>> allBalances = tradingConfig.getStrategyBalances();
            if (allBalances != null) {
                Map<StrategyType, BigDecimal> pairBalances = allBalances.get(pair);
                if (pairBalances != null && pairBalances.containsKey(strategyType)) {
                    return pairBalances.get(strategyType);
                }
            }
            return tradingConfig.getPaperBalance();
        }
        return fetchRealEurBalance();
    }

    private BigDecimal fetchRealEurBalance() {
        try {
            List<BalanceResponse> balances = marketDataClient.getBalances();
            return balances.stream()
                    .filter(b -> "EUR".equalsIgnoreCase(b.currency()))
                    .map(BalanceResponse::available)
                    .findFirst()
                    .orElseGet(() -> {
                        log.warn("EUR not found in Revolut balance response — defaulting to 0");
                        return BigDecimal.ZERO;
                    });
        } catch (Exception e) {
            log.error("Failed to fetch real EUR balance — defaulting to 0. Cause: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }
}
