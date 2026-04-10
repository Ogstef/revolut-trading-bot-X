package com.stefo.revolut_trading_bot.execution;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
import com.stefo.revolut_trading_bot.model.enums.SignalType;
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
 *   2. monitorPositions() — checks all open positions against TP/SL on each cycle
 *
 * In Phase 4, only PAPER mode is wired. LiveTradingService is added in Phase 7.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderExecutionService {

    private final RiskManager riskManager;
    private final PaperTradingService paperTradingService;
    private final TakeProfitStopLossManager tpslManager;
    private final PositionRepository positionRepository;
    private final TradingConfig config;

    /**
     * Processes a signal:
     *  - BUY  → validates risk, opens a new position if approved
     *  - SELL → closes any open position via SIGNAL_EXIT (in addition to TP/SL monitoring)
     *  - HOLD → no action
     *
     * @param signal           evaluated trading signal
     * @param availableBalance current EUR balance (real or paper)
     * @param currentPrice     current BTC-EUR market price
     * @return the opened Position, or empty if HOLD/risk rejected/SELL with nothing to close
     */
    @Transactional
    public Optional<Position> executeSignal(Signal signal, BigDecimal availableBalance,
                                            BigDecimal currentPrice) {
        log.info("ExecuteSignal: {} pair={} price={}", signal.type(), signal.pair(), currentPrice);

        if (signal.type() == SignalType.HOLD) {
            log.debug("Signal is HOLD — no action");
            return Optional.empty();
        }

        if (signal.type() == SignalType.SELL) {
            closeAllOpenPositions(currentPrice, TakeProfitStopLossManager.EXIT_SIGNAL);
            return Optional.empty();
        }

        // BUY — run risk checks first
        RiskValidationResult risk = riskManager.validate(availableBalance);
        if (!risk.approved()) {
            log.info("Trade blocked by risk manager: {}", risk.reason());
            return Optional.empty();
        }

        Position position = paperTradingService.openPosition(signal, risk.positionSizeEur(), currentPrice);
        return Optional.of(position);
    }

    /**
     * Scans all open positions and closes any that have hit their TP or SL.
     * Call this on every trading loop cycle before processing new signals.
     *
     * @param currentPrice current BTC-EUR market price
     */
    @Transactional
    public void monitorPositions(BigDecimal currentPrice) {
        List<Position> open = positionRepository.findByStatus(OrderStatus.OPEN);
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

    // ─── Private helpers ──────────────────────────────────────────────────────

    private void closeAllOpenPositions(BigDecimal currentPrice, String exitReason) {
        List<Position> open = positionRepository.findByStatus(OrderStatus.OPEN);
        if (open.isEmpty()) {
            log.debug("SELL signal — no open positions to close");
            return;
        }
        log.info("SELL signal — closing {} open position(s)", open.size());
        open.forEach(p -> paperTradingService.closePosition(p, currentPrice, exitReason));
    }
}
