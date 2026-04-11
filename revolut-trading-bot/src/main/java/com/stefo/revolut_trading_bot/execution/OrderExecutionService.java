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
     * Processes a signal using (pair, strategy)-scoped risk validation.
     *  - BUY  → validates risk, opens position if approved
     *  - SELL → closes all open positions for this (pair, strategy)
     *  - HOLD → no action
     *
     * @param signal           evaluated signal (carries pair and strategyType)
     * @param availableBalance EUR balance allocated to this (pair, strategy)
     * @param currentPrice     current market price for this pair
     */
    @Transactional
    public Optional<Position> executeSignal(Signal signal, BigDecimal availableBalance,
                                            BigDecimal currentPrice) {
        String pair          = signal.pair();
        StrategyType strategy = signal.strategyType();
        log.info("ExecuteSignal: {} pair={} strategy={} price={}", signal.type(), pair, strategy, currentPrice);

        if (signal.type() == SignalType.HOLD) {
            log.debug("Signal is HOLD — no action [pair={} strategy={}]", pair, strategy);
            return Optional.empty();
        }

        if (signal.type() == SignalType.SELL) {
            closePositions(pair, strategy, currentPrice, TakeProfitStopLossManager.EXIT_SIGNAL);
            return Optional.empty();
        }

        // BUY — run (pair, strategy)-scoped risk checks
        RiskValidationResult risk = riskManager.validateForStrategy(availableBalance, pair, strategy);
        if (!risk.approved()) {
            log.info("Trade blocked by risk manager [pair={} strategy={}]: {}", pair, strategy, risk.reason());
            return Optional.empty();
        }

        Position position = paperTradingService.openPosition(signal, risk.positionSizeEur(), currentPrice);
        return Optional.of(position);
    }

    /**
     * Scans open positions for the given (pair, strategy) and closes any that hit TP/SL.
     *
     * @param currentPrice current market price for this pair
     * @param pair         only positions for this pair are checked
     * @param strategyName only positions tagged with this strategy are checked
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

    private void closePositions(String pair, StrategyType strategyName,
                                BigDecimal currentPrice, String exitReason) {
        List<Position> open = positionRepository
                .findByStatusAndPairAndStrategyName(OrderStatus.OPEN, pair, strategyName);
        if (open.isEmpty()) {
            log.debug("SELL signal — no open positions to close [pair={} strategy={}]", pair, strategyName);
            return;
        }
        log.info("SELL signal — closing {} open position(s) [pair={} strategy={}]",
                open.size(), pair, strategyName);
        open.forEach(p -> paperTradingService.closePosition(p, currentPrice, exitReason));
    }
}
