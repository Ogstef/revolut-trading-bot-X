package com.stefo.revolut_trading_bot.execution;

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
 * Routes signal execution to the paper-trading service for SPOT trades.
 *
 * Trade lifecycle:
 *   BUY  → if no open position for this triple → open
 *   SELL → if open → close
 *   HOLD → no action
 *
 * Positions, trades, and TP/SL checks are all scoped to the
 * (pair, interval, strategy) triple.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderExecutionService {

    private final RiskManager               riskManager;
    private final PaperTradingService       paperTradingService;
    private final TakeProfitStopLossManager tpslManager;
    private final PositionRepository        positionRepository;

    @Transactional
    public Optional<Position> executeSignal(Signal signal, BigDecimal availableBalance,
                                            BigDecimal currentPrice) {
        String pair           = signal.pair();
        String interval       = signal.interval();
        StrategyType strategy = signal.strategyType();
        log.info("ExecuteSignal: {} pair={} interval={} strategy={} price={}",
                signal.type(), pair, interval, strategy, currentPrice);

        if (signal.type() == SignalType.HOLD) {
            return Optional.empty();
        }

        if (signal.type() == SignalType.SELL) {
            List<Position> open = positionRepository
                    .findByStatusAndPairAndIntervalAndStrategyName(
                            OrderStatus.OPEN, pair, interval, strategy);
            if (!open.isEmpty()) {
                log.info("SELL signal — closing {} open position(s) [pair={} interval={} strategy={}]",
                        open.size(), pair, interval, strategy);
                open.forEach(p -> paperTradingService.closePosition(
                        p, currentPrice, TakeProfitStopLossManager.EXIT_SIGNAL));
            }
            return Optional.empty();
        }

        // BUY
        RiskValidationResult risk = riskManager.validateForStrategy(availableBalance, pair, interval, strategy);
        if (!risk.approved()) {
            log.info("Trade blocked by risk manager [pair={} interval={} strategy={}]: {}",
                    pair, interval, strategy, risk.reason());
            return Optional.empty();
        }
        return Optional.of(paperTradingService.openPosition(signal, risk.positionSizeEur(), currentPrice));
    }

    /**
     * Scans open positions for the given (pair, interval, strategy) and closes
     * any that hit TP / SL.
     */
    @Transactional
    public void monitorPositions(BigDecimal currentPrice, String pair, String interval,
                                 StrategyType strategyName) {
        List<Position> open = positionRepository
                .findByStatusAndPairAndIntervalAndStrategyName(
                        OrderStatus.OPEN, pair, interval, strategyName);
        checkAndClosePositions(open, currentPrice);
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

    private void checkAndClosePositions(List<Position> open, BigDecimal currentPrice) {
        if (open.isEmpty()) return;
        log.debug("Monitoring {} open position(s) at price={}", open.size(), currentPrice);
        for (Position position : open) {
            tpslManager.checkExitCondition(position, currentPrice).ifPresent(exitReason ->
                    paperTradingService.closePosition(position, currentPrice, exitReason)
            );
        }
    }
}
