package com.stefo.revolut_trading_bot.strategy.impl;

import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.strategy.Signal;
import com.stefo.revolut_trading_bot.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ParabolicSarIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DecimalNum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Parabolic SAR strategy — trailing dot that flips sides when the trend reverses.
 *
 * Completely different signal shape from all other strategies: no oscillators,
 * no moving average lag. The SAR accelerates toward price as the trend strengthens,
 * then flips to the other side on a clean reversal candle.
 *
 * Indicators:
 *   ParabolicSAR with acceleration factor 0.02, max acceleration 0.20 (standard defaults)
 *
 * Signals:
 *   BUY  → price crosses above SAR (prev close ≤ prev SAR, current close > current SAR)
 *   SELL → price crosses below SAR (prev close ≥ prev SAR, current close < current SAR)
 *   HOLD → no flip this bar (SAR already on the same side as before)
 *
 * Signal field mapping:
 *   emaShort → current SAR value
 *   emaLong  → null
 *   rsi      → distance: (price − SAR) / price × 100  (positive = bullish gap, negative = bearish)
 */
@Slf4j
@Component
public class ParabolicSarStrategy implements TradingStrategy {

    // SAR needs a few bars to initialise; use a safe minimum
    private static final int MIN_BARS = 5;

    @Override
    public StrategyType strategyType() {
        return StrategyType.PARABOLIC_SAR;
    }

    @Override
    public Signal evaluate(BarSeries series, String pair) {
        Instant now    = Instant.now();
        int lastIdx    = series.getEndIndex();

        if (lastIdx < MIN_BARS) {
            String reason = String.format("PARABOLIC_SAR: insufficient data — %d bars, need %d",
                    lastIdx + 1, MIN_BARS + 1);
            log.warn(reason);
            return hold(reason, pair, now, null, null, null);
        }

        // acceleration factor = 0.02, max acceleration = 0.20
        ParabolicSarIndicator sar   = new ParabolicSarIndicator(series,
                DecimalNum.valueOf(0.02), DecimalNum.valueOf(0.20));
        ClosePriceIndicator   close = new ClosePriceIndicator(series);

        int prevIdx = lastIdx - 1;

        double sarNow    = sar.getValue(lastIdx).doubleValue();
        double sarPrev   = sar.getValue(prevIdx).doubleValue();
        double priceNow  = close.getValue(lastIdx).doubleValue();
        double pricePrev = close.getValue(prevIdx).doubleValue();

        BigDecimal sarVal    = bd(sarNow);
        BigDecimal priceVal  = bd(priceNow);
        // Distance: positive when price > SAR (bullish), negative when price < SAR (bearish)
        BigDecimal distance  = priceNow == 0 ? BigDecimal.ZERO
                : bd((priceNow - sarNow) / priceNow * 100);

        log.info("[PARABOLIC_SAR] sar={} price={} distance={}%", sarVal, priceVal, distance);

        // BUY flip: was below or at SAR, now above SAR
        if (pricePrev <= sarPrev && priceNow > sarNow) {
            String reason = String.format("Price flipped above SAR — price=%.2f sar=%.2f gap=%.2f%%",
                    priceNow, sarNow, distance.doubleValue());
            return new Signal(SignalType.BUY, BigDecimal.valueOf(75), reason,
                    pair, null, StrategyType.PARABOLIC_SAR, now, sarVal, null, distance, priceVal);
        }

        // SELL flip: was above or at SAR, now below SAR
        if (pricePrev >= sarPrev && priceNow < sarNow) {
            String reason = String.format("Price flipped below SAR — price=%.2f sar=%.2f gap=%.2f%%",
                    priceNow, sarNow, distance.doubleValue());
            return new Signal(SignalType.SELL, BigDecimal.valueOf(71), reason,
                    pair, null, StrategyType.PARABOLIC_SAR, now, sarVal, null, distance, priceVal);
        }

        String side   = priceNow > sarNow ? "above" : "below";
        String reason = String.format("No SAR flip — price %s SAR (price=%.2f sar=%.2f)",
                side, priceNow, sarNow);
        return hold(reason, pair, now, sarVal, distance, priceVal);
    }

    private Signal hold(String reason, String pair, Instant evaluatedAt,
                        BigDecimal sar, BigDecimal distance, BigDecimal price) {
        return new Signal(SignalType.HOLD, BigDecimal.valueOf(50), reason,
                pair, null, StrategyType.PARABOLIC_SAR, evaluatedAt, sar, null, distance, price);
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }
}
