package com.stefo.revolut_trading_bot.strategy.impl;

import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.strategy.Signal;
import com.stefo.revolut_trading_bot.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.donchian.DonchianChannelLowerIndicator;
import org.ta4j.core.indicators.donchian.DonchianChannelUpperIndicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Donchian Channel breakout strategy — the original "turtle trader" strategy.
 *
 * Buys when price breaks above the highest high of the last 20 bars,
 * sells when price breaks below the lowest low. Pure breakout — no oscillators,
 * no lagging averages. Generates far fewer signals; each is a genuine new high/low.
 *
 * Signals:
 *   BUY  → current close > upper band (price just made a new 20-bar high)
 *   SELL → current close < lower band (price just made a new 20-bar low)
 *   HOLD → price inside the channel
 *
 * Signal field mapping:
 *   emaShort → upper channel value
 *   emaLong  → lower channel value
 *   rsi      → channel width as % of price ((upper - lower) / close × 100)
 */
@Slf4j
@Component
public class DonchianStrategy implements TradingStrategy {

    private static final int PERIOD   = 20;
    private static final int MIN_BARS = PERIOD + 1; // need prev bar for crossover check

    @Override
    public StrategyType strategyType() {
        return StrategyType.DONCHIAN;
    }

    @Override
    public Signal evaluate(BarSeries series, String pair) {
        Instant now  = Instant.now();
        int lastIdx  = series.getEndIndex();

        if (lastIdx < MIN_BARS) {
            String reason = String.format("DONCHIAN: insufficient data — %d bars, need %d",
                    lastIdx + 1, MIN_BARS + 1);
            log.warn(reason);
            return hold(reason, pair, now, null, null, null, null);
        }

        DonchianChannelUpperIndicator upper = new DonchianChannelUpperIndicator(series, PERIOD);
        DonchianChannelLowerIndicator lower = new DonchianChannelLowerIndicator(series, PERIOD);

        int prevIdx = lastIdx - 1;

        double closeNow  = series.getBar(lastIdx).getClosePrice().doubleValue();
        double closePrev = series.getBar(prevIdx).getClosePrice().doubleValue();
        double upperNow  = upper.getValue(lastIdx).doubleValue();
        double upperPrev = upper.getValue(prevIdx).doubleValue();
        double lowerNow  = lower.getValue(lastIdx).doubleValue();
        double lowerPrev = lower.getValue(prevIdx).doubleValue();

        BigDecimal upperVal = bd(upperNow);
        BigDecimal lowerVal = bd(lowerNow);
        BigDecimal priceVal = bd(closeNow);

        // Channel width as % of price
        BigDecimal widthPct = closeNow > 0
                ? bd((upperNow - lowerNow) / closeNow * 100.0)
                : null;

        log.info("[DONCHIAN] pair={} close={} upper={} lower={} widthPct={}", pair, priceVal, upperVal, lowerVal, widthPct);

        // Breakout to the upside: prev close was inside/below prev channel, now close exceeds prev upper.
        // We compare closeNow against upperPrev (not upperNow) because the channel always includes the
        // current bar's own high, making closeNow > upperNow mathematically impossible.
        if (closePrev <= upperPrev && closeNow > upperPrev) {
            String reason = String.format("Donchian breakout UP — close=%.2f > prev upper=%.2f (new 20-bar high)",
                    closeNow, upperPrev);
            return new Signal(SignalType.BUY, BigDecimal.valueOf(77), reason,
                    pair, null, StrategyType.DONCHIAN, now, upperVal, lowerVal, widthPct, priceVal);
        }

        // Breakout to the downside: prev close was inside/above prev channel, now close falls below prev lower.
        if (closePrev >= lowerPrev && closeNow < lowerPrev) {
            String reason = String.format("Donchian breakout DOWN — close=%.2f < prev lower=%.2f (new 20-bar low)",
                    closeNow, lowerPrev);
            return new Signal(SignalType.SELL, BigDecimal.valueOf(73), reason,
                    pair, null, StrategyType.DONCHIAN, now, upperVal, lowerVal, widthPct, priceVal);
        }

        String reason = String.format("Donchian inside channel — close=%.2f in [%.2f, %.2f]",
                closeNow, lowerNow, upperNow);
        return hold(reason, pair, now, upperVal, lowerVal, widthPct, priceVal);
    }

    private Signal hold(String reason, String pair, Instant evaluatedAt,
                        BigDecimal upper, BigDecimal lower, BigDecimal widthPct, BigDecimal price) {
        return new Signal(SignalType.HOLD, BigDecimal.valueOf(50), reason,
                pair, null, StrategyType.DONCHIAN, evaluatedAt, upper, lower, widthPct, price);
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }
}
