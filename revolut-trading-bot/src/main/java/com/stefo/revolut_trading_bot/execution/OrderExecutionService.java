package com.stefo.revolut_trading_bot.execution;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.model.enums.TradingVehicle;
import com.stefo.revolut_trading_bot.portfolio.VehicleBalanceService;
import com.stefo.revolut_trading_bot.repository.PositionRepository;
import com.stefo.revolut_trading_bot.risk.RiskManager;
import com.stefo.revolut_trading_bot.risk.RiskValidationResult;
import com.stefo.revolut_trading_bot.risk.TakeProfitStopLossManager;
import com.stefo.revolut_trading_bot.strategy.Signal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Routes signal execution to the correct trading mode (PAPER or LIVE) and vehicle (SPOT or LEV_*X).
 *
 * From Phase 13, every call site carries a {@link TradingVehicle}:
 *   - SPOT → PaperTradingService (long-only, circuit-breaker-gated via RiskManager)
 *   - LEV_*X → LeveragedPaperTradingService (long + short, gated only by VehicleBalanceService)
 *
 * Trade lifecycle per vehicle:
 *   BUY  → if no open position for this quadruple → open (long)
 *   SELL → if open (long) → close; else (leveraged only, allow-shorts) → open short
 *   HOLD → no action
 *
 * Positions, trades, TP/SL, and liquidation checks are all scoped to the
 * (pair, interval, strategy, vehicle) quadruple.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderExecutionService {

    private final RiskManager                  riskManager;
    private final PaperTradingService          paperTradingService;
    private final LeveragedPaperTradingService leveragedPaperTradingService;
    private final VehicleBalanceService        vehicleBalanceService;
    private final LeverageCooldownService      cooldownService;
    private final TakeProfitStopLossManager    tpslManager;
    private final PositionRepository           positionRepository;
    private final TradingConfig                config;

    // ─── SPOT-scoped (backward-compat wrappers) ──────────────────────────────

    /**
     * SPOT-scoped signal execution — kept for backward compatibility.
     * Legacy callers that don't carry a vehicle dimension get this.
     */
    @Transactional
    public Optional<Position> executeSignal(Signal signal, BigDecimal availableBalance,
                                            BigDecimal currentPrice) {
        return executeSignalForVehicle(signal, TradingVehicle.SPOT, availableBalance, currentPrice);
    }

    @Transactional
    public void monitorPositions(BigDecimal currentPrice, String pair, String interval, StrategyType strategyName) {
        monitorPositionsForVehicle(currentPrice, pair, interval, strategyName, TradingVehicle.SPOT);
    }

    // ─── Vehicle-scoped API ──────────────────────────────────────────────────

    /**
     * Processes a signal for a specific (pair, interval, strategy, vehicle) quadruple.
     *
     *   - BUY on SPOT     → risk-gated open via PaperTradingService
     *   - BUY on LEV_*X   → balance-gated open via LeveragedPaperTradingService
     *   - SELL with open  → close via the matching service
     *   - SELL no-open on LEV_*X (allow-shorts) → open short
     *   - SELL no-open on SPOT → no-op (unchanged)
     *   - HOLD → no action
     */
    @Transactional
    public Optional<Position> executeSignalForVehicle(Signal signal, TradingVehicle vehicle,
                                                      BigDecimal availableBalance, BigDecimal currentPrice) {
        String pair           = signal.pair();
        String interval       = signal.interval();
        StrategyType strategy = signal.strategyType();
        log.info("ExecuteSignal: {} pair={} interval={} strategy={} vehicle={} price={}",
                signal.type(), pair, interval, strategy, vehicle, currentPrice);

        if (signal.type() == SignalType.HOLD) {
            return Optional.empty();
        }

        if (signal.type() == SignalType.SELL) {
            List<Position> open = positionRepository
                    .findByStatusAndPairAndIntervalAndStrategyNameAndVehicle(
                            OrderStatus.OPEN, pair, interval, strategy, vehicle);
            if (!open.isEmpty()) {
                log.info("SELL signal — closing {} open {} position(s) [pair={} interval={} strategy={}]",
                        open.size(), vehicle, pair, interval, strategy);
                open.forEach(p -> closeByVehicle(p, currentPrice, TakeProfitStopLossManager.EXIT_SIGNAL));
                return Optional.empty();
            }
            // Leveraged-only: open a SHORT when there's no long to close and shorts are allowed.
            if (vehicle.isLeveraged() && config.getLeverage().isAllowShorts()) {
                return openLeveraged(signal, vehicle, currentPrice);
            }
            return Optional.empty();
        }

        // BUY
        if (vehicle == TradingVehicle.SPOT) {
            RiskValidationResult risk = riskManager.validateForStrategy(availableBalance, pair, interval, strategy);
            if (!risk.approved()) {
                log.info("Trade blocked by risk manager [pair={} interval={} strategy={} vehicle=SPOT]: {}",
                        pair, interval, strategy, risk.reason());
                return Optional.empty();
            }
            return Optional.of(paperTradingService.openPosition(signal, risk.positionSizeEur(), currentPrice));
        }
        return openLeveraged(signal, vehicle, currentPrice);
    }

    /**
     * Scans open positions for the given (pair, interval, strategy, vehicle) and closes
     * any that hit TP / SL / LIQUIDATED.
     */
    @Transactional
    public void monitorPositionsForVehicle(BigDecimal currentPrice, String pair, String interval,
                                           StrategyType strategyName, TradingVehicle vehicle) {
        List<Position> open = positionRepository
                .findByStatusAndPairAndIntervalAndStrategyNameAndVehicle(
                        OrderStatus.OPEN, pair, interval, strategyName, vehicle);
        if (open.isEmpty()) return;
        log.debug("Monitoring {} open {} position(s) at price={}", open.size(), vehicle, currentPrice);
        for (Position position : open) {
            tpslManager.checkExitCondition(position, currentPrice).ifPresent(exitReason ->
                    closeByVehicle(position, currentPrice, exitReason)
            );
        }
    }

    // ─── Legacy all-open monitors (test endpoints) ───────────────────────────

    @Transactional
    public void monitorPositions(BigDecimal currentPrice, String pair, StrategyType strategyName) {
        List<Position> open = positionRepository
                .findByStatusAndPairAndStrategyName(OrderStatus.OPEN, pair, strategyName);
        checkAndClosePositions(open, currentPrice);
    }

    @Transactional
    public void monitorPositions(BigDecimal currentPrice) {
        List<Position> open = positionRepository.findByStatus(OrderStatus.OPEN);
        checkAndClosePositions(open, currentPrice);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private Optional<Position> openLeveraged(Signal signal, TradingVehicle vehicle, BigDecimal currentPrice) {
        String pair           = signal.pair();
        String interval       = signal.interval();
        StrategyType strategy = signal.strategyType();

        // Strategy blacklist — fee-victim strategies configured out of leveraged trading.
        // Existing open positions on these strategies still close naturally; only new opens are blocked.
        if (config.getLeverage().getExcludedStrategies().contains(strategy)) {
            log.debug("Leveraged trade blocked: strategy {} excluded on leverage [pair={} interval={} vehicle={}]",
                    strategy, pair, interval, vehicle);
            return Optional.empty();
        }

        // Post-close cooldown — prevents every-30-second re-entry churn after a close
        // on the same (pair, interval, strategy, vehicle) quadruple.
        if (!cooldownService.canOpen(pair, interval, strategy, vehicle, LocalDateTime.now())) {
            log.debug("Leveraged trade blocked: cooldown active [pair={} interval={} strategy={} vehicle={} cooldown={}m]",
                    pair, interval, strategy, vehicle, config.getLeverage().getCooldownMinutes());
            return Optional.empty();
        }

        // Cap concurrent leveraged positions per (pair, interval, strategy, vehicle).
        long openCount = positionRepository.countByStatusAndPairAndIntervalAndStrategyNameAndVehicle(
                OrderStatus.OPEN, pair, interval, strategy, vehicle);
        if (openCount >= config.getLeverage().getMaxConcurrentPositions()) {
            log.info("Leveraged trade blocked: max concurrent positions ({}) reached [pair={} interval={} strategy={} vehicle={}]",
                    openCount, pair, interval, strategy, vehicle);
            return Optional.empty();
        }

        // Balance gate — collateral is 100% of the max per-trade budget by default.
        BigDecimal available = vehicleBalanceService.available(pair, strategy, vehicle);
        BigDecimal collateral = available
                .multiply(config.getLeverage().getMaxPositionPct())
                .divide(BigDecimal.valueOf(100), 8, java.math.RoundingMode.HALF_UP);
        if (collateral.signum() <= 0) {
            log.info("Leveraged trade blocked: no available collateral [pair={} interval={} strategy={} vehicle={} available={}]",
                    pair, interval, strategy, vehicle, available);
            return Optional.empty();
        }

        Position p = leveragedPaperTradingService.openPosition(signal, collateral, currentPrice, vehicle);
        return Optional.of(p);
    }

    private void closeByVehicle(Position position, BigDecimal currentPrice, String exitReason) {
        if (position.getVehicle() == null || position.getVehicle() == TradingVehicle.SPOT) {
            paperTradingService.closePosition(position, currentPrice, exitReason);
        } else {
            leveragedPaperTradingService.closePosition(position, currentPrice, exitReason);
        }
    }

    private void checkAndClosePositions(List<Position> open, BigDecimal currentPrice) {
        if (open.isEmpty()) return;
        log.debug("Monitoring {} open position(s) at price={}", open.size(), currentPrice);
        for (Position position : open) {
            tpslManager.checkExitCondition(position, currentPrice).ifPresent(exitReason ->
                    closeByVehicle(position, currentPrice, exitReason)
            );
        }
    }
}
