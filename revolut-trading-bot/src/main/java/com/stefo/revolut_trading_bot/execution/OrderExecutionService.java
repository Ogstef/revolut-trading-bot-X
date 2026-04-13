package com.stefo.revolut_trading_bot.execution;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
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
import java.util.List;
import java.util.Optional;

/**
 * Routes signal execution to the correct trading mode (PAPER or LIVE).
 *
 * Responsibilities:
 *   1. executeSignal() — opens or closes a position based on the incoming signal
 *   2. monitorPositions() — checks open positions for the given (pair, strategy) against TP/SL
 *
 * From Phase 8, all queries are scoped to a specific pair so that BTC-EUR and ETH-EUR
 * positions are fully isolated even when using the same strategy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderExecutionService {

    private final RiskManager             riskManager;
    private final PaperTradingService     paperTradingService;
    private final TakeProfitStopLossManager tpslManager;
    private final PositionRepository      positionRepository;
    private final TradingConfig           config;

    /**
     * Processes a signal using (pair, interval, strategy)-scoped risk validation.
     *  - BUY  → validates risk, opens position if approved
     *  - SELL → closes all open positions for this (pair, interval, strategy)
     *  - HOLD → no action
     *
     * @param signal           evaluated signal (carries pair, interval, and strategyType)
     * @param availableBalance EUR balance allocated to this (pair, strategy)
     * @param currentPrice     current market price for this pair
     */
    @Transactional
    public Optional<Position> executeSignal(Signal signal, BigDecimal availableBalance,
                                            BigDecimal currentPrice) {
        String pair           = signal.pair();
        String interval       = signal.interval();
        StrategyType strategy = signal.strategyType();
        log.info("ExecuteSignal: {} pair={} interval={} strategy={} price={}",
                signal.type(), pair, interval, strategy, currentPrice);

        if (signal.type() == SignalType.HOLD) {
            log.debug("Signal is HOLD — no action [pair={} interval={} strategy={}]", pair, interval, strategy);
            return Optional.empty();
        }

        if (signal.type() == SignalType.SELL) {
            closePositions(pair, interval, strategy, currentPrice, TakeProfitStopLossManager.EXIT_SIGNAL);
            return Optional.empty();
        }

        // BUY — run (pair, interval, strategy)-scoped risk checks
        RiskValidationResult risk = riskManager.validateForStrategy(availableBalance, pair, interval, strategy);
        if (!risk.approved()) {
            log.info("Trade blocked by risk manager [pair={} interval={} strategy={}]: {}",
                    pair, interval, strategy, risk.reason());
            return Optional.empty();
        }

        Position position = paperTradingService.openPosition(signal, risk.positionSizeEur(), currentPrice);
        return Optional.of(position);
    }

    /**
     * Scans open positions for the given (pair, interval, strategy) and closes any that hit TP/SL.
     */
    @Transactional
    public void monitorPositions(BigDecimal currentPrice, String pair, String interval, StrategyType strategyName) {
        List<Position> open = positionRepository
                .findByStatusAndPairAndIntervalAndStrategyName(OrderStatus.OPEN, pair, interval, strategyName);
        checkAndClosePositions(open, currentPrice);
    }

    /**
     * Backward-compatible (pair, strategy) scoped monitor — uses all intervals.
     */
    @Transactional
    public void monitorPositions(BigDecimal currentPrice, String pair, StrategyType strategyName) {
        List<Position> open = positionRepository
                .findByStatusAndPairAndStrategyName(OrderStatus.OPEN, pair, strategyName);
        checkAndClosePositions(open, currentPrice);
    }

    /**
     * Scans ALL open positions (all pairs, all strategies) for TP/SL.
     * Kept for legacy callers and test endpoints.
     */
    @Transactional
    public void monitorPositions(BigDecimal currentPrice) {
        List<Position> open = positionRepository.findByStatus(OrderStatus.OPEN);
        checkAndClosePositions(open, currentPrice);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private void checkAndClosePositions(List<Position> open, BigDecimal currentPrice) {
        if (open.isEmpty()) {
            return;
        }
        log.debug("Monitoring {} open position(s) at price={}", open.size(), currentPrice);
        for (Position position : open) {
            tpslManager.checkExitCondition(position, currentPrice).ifPresent(exitReason ->
                    paperTradingService.closePosition(position, currentPrice, exitReason)
            );
        }
    }

    private void closePositions(String pair, String interval, StrategyType strategyName,
                                BigDecimal currentPrice, String exitReason) {
        List<Position> open = positionRepository
                .findByStatusAndPairAndIntervalAndStrategyName(OrderStatus.OPEN, pair, interval, strategyName);
        if (open.isEmpty()) {
            log.debug("SELL signal — no open positions to close [pair={} interval={} strategy={}]",
                    pair, interval, strategyName);
            return;
        }
        log.info("SELL signal — closing {} open position(s) [pair={} interval={} strategy={}]",
                open.size(), pair, interval, strategyName);
        open.forEach(p -> paperTradingService.closePosition(p, currentPrice, exitReason));
    }
}
