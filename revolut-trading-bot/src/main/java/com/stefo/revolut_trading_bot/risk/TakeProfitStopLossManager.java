package com.stefo.revolut_trading_bot.risk;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.enums.OrderSide;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Calculates take-profit / stop-loss prices and checks whether an open
 * position has hit either threshold given the current market price.
 *
 * Uses percentages from TradingConfig:
 *   Take Profit = entryPrice × (1 + takeProfitPct / 100)   → default 5%
 *   Stop Loss   = entryPrice × (1 - stopLossPct  / 100)   → default 3%
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TakeProfitStopLossManager {

    public static final String EXIT_TAKE_PROFIT  = "TP_HIT";
    public static final String EXIT_STOP_LOSS    = "SL_HIT";
    public static final String EXIT_SIGNAL       = "SIGNAL_EXIT";
    public static final String EXIT_MANUAL       = "MANUAL";

    private final TradingConfig config;

    /** TP price for a BUY position: entry × (1 + pct%) */
    public BigDecimal calculateTakeProfit(BigDecimal entryPrice) {
        BigDecimal multiplier = BigDecimal.ONE
                .add(config.getRisk().getTakeProfitPct()
                        .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
        return entryPrice.multiply(multiplier).setScale(8, RoundingMode.HALF_UP);
    }

    /** SL price for a BUY position: entry × (1 - pct%) */
    public BigDecimal calculateStopLoss(BigDecimal entryPrice) {
        BigDecimal multiplier = BigDecimal.ONE
                .subtract(config.getRisk().getStopLossPct()
                        .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
        return entryPrice.multiply(multiplier).setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * Checks whether a position should be closed at the current market price.
     *
     * @return Optional with the exit reason ("TP_HIT" / "SL_HIT"), or empty if no exit triggered.
     */
    public Optional<String> checkExitCondition(Position position, BigDecimal currentPrice) {
        if (position.getSide() == OrderSide.BUY) {
            if (currentPrice.compareTo(position.getTakeProfit()) >= 0) {
                log.info("TP_HIT for position {} — price={} tp={}", position.getId(),
                        currentPrice, position.getTakeProfit());
                return Optional.of(EXIT_TAKE_PROFIT);
            }
            if (currentPrice.compareTo(position.getStopLoss()) <= 0) {
                log.info("SL_HIT for position {} — price={} sl={}", position.getId(),
                        currentPrice, position.getStopLoss());
                return Optional.of(EXIT_STOP_LOSS);
            }
        } else {
            // SELL (short) — profits when price drops
            if (currentPrice.compareTo(position.getTakeProfit()) <= 0) {
                log.info("TP_HIT (short) for position {} — price={} tp={}", position.getId(),
                        currentPrice, position.getTakeProfit());
                return Optional.of(EXIT_TAKE_PROFIT);
            }
            if (currentPrice.compareTo(position.getStopLoss()) >= 0) {
                log.info("SL_HIT (short) for position {} — price={} sl={}", position.getId(),
                        currentPrice, position.getStopLoss());
                return Optional.of(EXIT_STOP_LOSS);
            }
        }
        return Optional.empty();
    }
}
