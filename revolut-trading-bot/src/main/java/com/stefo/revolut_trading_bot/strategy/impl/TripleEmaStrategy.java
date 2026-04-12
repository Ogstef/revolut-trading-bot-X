package com.stefo.revolut_trading_bot.strategy.impl;

import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.strategy.Signal;
import com.stefo.revolut_trading_bot.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Triple EMA strategy — all three EMAs must align for a signal.
 *
 * Filters out the false crossovers that the 2-EMA strategy suffers from in
 * choppy, sideways markets. The three-way stack confirmation means signals
 * fire less often but with higher conviction.
 *
 * Indicators: EMA(5), EMA(13), EMA(34)
 *
 * Signals:
 *   BUY  → EMA5 > EMA13 > EMA34 (full bullish stack on this bar)
 *   SELL → EMA5 < EMA13 < EMA34 (full bearish stack on this bar)
 *   HOLD → mixed ordering (sideways — no trade)
 *
 * Signal field mapping:
 *   emaShort → EMA5 value
 *   emaLong  → EMA34 value
 *   rsi      → EMA13 value (middle band — repurposed)
 */
@Slf4j
@Component
public class TripleEmaStrategy implements TradingStrategy {

    private static final int FAST_PERIOD   = 5;
    private static final int MID_PERIOD    = 13;
    private static final int SLOW_PERIOD   = 34;

    // Need SLOW_PERIOD bars minimum
    private static final int MIN_BARS = SLOW_PERIOD;

    @Override
    public StrategyType strategyType() {
        return StrategyType.TRIPLE_EMA;
    }

    @Override
    public Signal evaluate(BarSeries series, String pair) {
        Instant now  = Instant.now();
        int lastIdx  = series.getEndIndex();

        if (lastIdx < MIN_BARS) {
            String reason = String.format("TRIPLE_EMA: insufficient data — %d bars, need %d",
                    lastIdx + 1, MIN_BARS + 1);
            log.warn(reason);
            return hold(reason, pair, now, null, null, null, null);
        }

        ClosePriceIndicator close  = new ClosePriceIndicator(series);
        EMAIndicator        ema5   = new EMAIndicator(close, FAST_PERIOD);
        EMAIndicator        ema13  = new EMAIndicator(close, MID_PERIOD);
        EMAIndicator        ema34  = new EMAIndicator(close, SLOW_PERIOD);

        BigDecimal e5  = bd(ema5.getValue(lastIdx).doubleValue());
        BigDecimal e13 = bd(ema13.getValue(lastIdx).doubleValue());
        BigDecimal e34 = bd(ema34.getValue(lastIdx).doubleValue());
        BigDecimal price = bd(close.getValue(lastIdx).doubleValue());

        log.info("[TRIPLE_EMA] ema5={} ema13={} ema34={} price={}", e5, e13, e34, price);

        // Full bullish stack: EMA5 > EMA13 > EMA34
        if (e5.compareTo(e13) > 0 && e13.compareTo(e34) > 0) {
            String reason = String.format("Bullish stack — EMA5=%.2f > EMA13=%.2f > EMA34=%.2f",
                    e5.doubleValue(), e13.doubleValue(), e34.doubleValue());
            return new Signal(SignalType.BUY, BigDecimal.valueOf(76), reason,
                    pair, StrategyType.TRIPLE_EMA, now, e5, e34, e13, price);
        }

        // Full bearish stack: EMA5 < EMA13 < EMA34
        if (e5.compareTo(e13) < 0 && e13.compareTo(e34) < 0) {
            String reason = String.format("Bearish stack — EMA5=%.2f < EMA13=%.2f < EMA34=%.2f",
                    e5.doubleValue(), e13.doubleValue(), e34.doubleValue());
            return new Signal(SignalType.SELL, BigDecimal.valueOf(72), reason,
                    pair, StrategyType.TRIPLE_EMA, now, e5, e34, e13, price);
        }

        String reason = String.format("Mixed EMA order — EMA5=%.2f EMA13=%.2f EMA34=%.2f (sideways)",
                e5.doubleValue(), e13.doubleValue(), e34.doubleValue());
        return hold(reason, pair, now, e5, e34, e13, price);
    }

    private Signal hold(String reason, String pair, Instant evaluatedAt,
                        BigDecimal e5, BigDecimal e34, BigDecimal e13, BigDecimal price) {
        return new Signal(SignalType.HOLD, BigDecimal.valueOf(50), reason,
                pair, StrategyType.TRIPLE_EMA, evaluatedAt, e5, e34, e13, price);
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }
}
