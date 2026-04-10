package com.stefo.revolut_trading_bot.scheduler;

import com.stefo.revolut_trading_bot.alert.AlertService;
import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.execution.OrderExecutionService;
import com.stefo.revolut_trading_bot.market.MarketDataClient;
import com.stefo.revolut_trading_bot.model.dto.BalanceResponse;
import com.stefo.revolut_trading_bot.portfolio.PortfolioService;
import com.stefo.revolut_trading_bot.risk.RiskManager;
import com.stefo.revolut_trading_bot.strategy.Signal;
import com.stefo.revolut_trading_bot.strategy.SignalEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Main trading heartbeat — runs every N seconds (configured via trading.polling-interval-seconds).
 *
 * Cycle order matters:
 *   1. Evaluate signal  — fetch fresh candles + run strategy (also updates bar series)
 *   2. Monitor TP/SL    — check open positions BEFORE acting on new signal
 *                         (a position may have already hit SL even if signal says HOLD)
 *   3. Resolve balance  — PAPER mode uses trading.paper-balance; LIVE mode fetches real EUR balance
 *   4. Execute signal   — open/close positions based on signal + risk approval
 *   5. Log portfolio    — snapshot of current state for visibility
 *
 * All exceptions are caught and logged — a bad cycle never stops future cycles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingLoop {

    private static final String MODE_PAPER = "PAPER";

    private final SignalEngine signalEngine;
    private final OrderExecutionService orderExecutionService;
    private final RiskManager riskManager;
    private final PortfolioService portfolioService;
    private final MarketDataClient marketDataClient;
    private final TradingConfig tradingConfig;
    private final BotStateService botStateService;
    private final AlertService alertService;

    // fixedDelay means: wait N seconds AFTER the previous cycle finishes before starting the next.
    // This avoids overlap if a cycle takes longer than the interval (e.g. slow API response).
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
        }
    }

    // ─── Private ─────────────────────────────────────────────────────────────

    private void runCycle() {
        log.info("══════════ Trading cycle start ══════════");

        // Step 1: evaluate signal (fetches fresh candles + runs EMA/RSI strategy)
        Signal signal = signalEngine.evaluateAndPersist();
        BigDecimal currentPrice = signal.currentPrice();
        log.info("Signal: {} | price: {} | reason: {}", signal.type(), currentPrice, signal.reason());

        // Step 2: check TP/SL on all open positions (runs regardless of signal type)
        orderExecutionService.monitorPositions(currentPrice);

        // Step 3: resolve balance — paper uses configured fake money, live uses real Revolut balance
        BigDecimal balance = resolveBalance();
        log.info("Balance: {} EUR [mode={}]", balance, tradingConfig.getMode());

        // Step 4: log risk state so circuit breakers are visible in logs
        RiskManager.RiskStatus riskStatus = riskManager.currentStatus(balance);
        log.info("Risk — openPositions: {} | dailyPnl: {} | consecutiveLosses: {} | circuitBreaker: {}",
                riskStatus.openPositions(), riskStatus.dailyPnl(),
                riskStatus.consecutiveLosses(), riskStatus.anyCircuitBreakerTripped());

        if (riskStatus.anyCircuitBreakerTripped()) {
            log.warn("⚠ Circuit breaker active — skipping trade execution this cycle");
            alertService.circuitBreakerTripped("openPositions=" + riskStatus.openPositions()
                    + " dailyPnl=" + riskStatus.dailyPnl()
                    + " consecutiveLosses=" + riskStatus.consecutiveLosses());
        }

        // Step 5: execute signal (RiskManager re-validates internally before opening)
        orderExecutionService.executeSignal(signal, balance, currentPrice);

        // Step 6: log portfolio snapshot
        var snapshot = portfolioService.getSnapshot(currentPrice);
        log.info("Portfolio — openPositions: {} | invested: {} EUR | unrealisedPnl: {} EUR ({}%)",
                snapshot.openPositions(), snapshot.totalInvested(),
                snapshot.unrealisedPnl(), snapshot.unrealisedPnlPct());

        log.info("══════════ Trading cycle end ══════════");
    }

    /**
     * In PAPER mode: returns the configured paper balance (trading.paper-balance).
     * In LIVE mode:  fetches the real EUR balance from the Revolut API.
     *
     * This keeps paper runs completely independent of your real account balance,
     * letting you test with any amount (e.g. €1,000, €10,000, €100,000).
     */
    private BigDecimal resolveBalance() {
        if (MODE_PAPER.equalsIgnoreCase(tradingConfig.getMode())) {
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
