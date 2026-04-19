package com.stefo.revolut_trading_bot.strategy.impl;

import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.strategy.Signal;
import com.stefo.revolut_trading_bot.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Supertrend strategy — ATR-based trailing band that flips sides on trend reversal.
 *
 * Addresses MACD's weakness: ATR scaling adapts to volatility so signals are less
 * frequent but cleaner. A single flip event = entry, no ambiguous crossover zone.
 *
 * Algorithm:
 *   HL2 = (High + Low) / 2
 *   Basic Upper = HL2 + multiplier * ATR(period)
 *   Basic Lower = HL2 - multiplier * ATR(period)
 *   Final Upper: tightens down each bar; resets to basicUpper when price breaks above it
 *   Final Lower: tightens up each bar; resets to basicLower when price breaks below it
 *   Trend: bullish when close >= finalLower, bearish when close <= finalUpper
 *
 * Signals:
 *   BUY  → trend flips bearish → bullish (close crosses above supertrend line)
 *   SELL → trend flips bullish → bearish (close crosses below supertrend line)
 *   HOLD → no flip this bar
 *
 * Signal field mapping:
 *   emaShort → supertrend line value
 *   emaLong  → ATR value
 *   rsi      → distance: (close - supertrend) / close × 100  (positive = bullish)
 */
@Slf4j
@Component
public class SupertrendStrategy implements TradingStrategy {

    private static final int    ATR_PERIOD  = 10;
    private static final double MULTIPLIER  = 3.0;
    // Need ATR_PERIOD valid ATR values + 2 Supertrend bars (current + prev) for flip detection
    private static final int    MIN_BARS    = ATR_PERIOD + 2;

    @Override
    public StrategyType strategyType() {
        return StrategyType.SUPERTREND;
    }

    @Override
    public Signal evaluate(BarSeries series, String pair) {
        Instant now    = Instant.now();
        int lastIdx    = series.getEndIndex();
        int beginIdx   = series.getBeginIndex();

        if (lastIdx - beginIdx < MIN_BARS) {
            String reason = String.format("SUPERTREND: insufficient data — %d bars, need %d",
                    lastIdx - beginIdx + 1, MIN_BARS + 1);
            log.warn(reason);
            return hold(reason, pair, now, null, null, null, null);
        }

        ATRIndicator atr      = new ATRIndicator(series, ATR_PERIOD);
        int          startIdx = beginIdx + ATR_PERIOD;
        int          len      = lastIdx - startIdx + 1;

        double[] finalUpper  = new double[len];
        double[] finalLower  = new double[len];
        boolean[] isBullish  = new boolean[len];

        for (int i = 0; i < len; i++) {
            int barIdx = startIdx + i;
            double high  = series.getBar(barIdx).getHighPrice().doubleValue();
            double low   = series.getBar(barIdx).getLowPrice().doubleValue();
            double close = series.getBar(barIdx).getClosePrice().doubleValue();
            double atrV  = atr.getValue(barIdx).doubleValue();
            double hl2   = (high + low) / 2.0;

            double basicUpper = hl2 + MULTIPLIER * atrV;
            double basicLower = hl2 - MULTIPLIER * atrV;

            if (i == 0) {
                finalUpper[i] = basicUpper;
                finalLower[i] = basicLower;
                isBullish[i]  = close >= hl2;
            } else {
                double prevClose = series.getBar(barIdx - 1).getClosePrice().doubleValue();

                // Upper band tightens down; only resets if price broke through it last bar
                finalUpper[i] = (basicUpper < finalUpper[i - 1] || prevClose > finalUpper[i - 1])
                        ? basicUpper : finalUpper[i - 1];

                // Lower band tightens up; only resets if price broke through it last bar
                finalLower[i] = (basicLower > finalLower[i - 1] || prevClose < finalLower[i - 1])
                        ? basicLower : finalLower[i - 1];

                if (isBullish[i - 1]) {
                    // Stay bullish unless price falls below the lower band
                    isBullish[i] = close >= finalLower[i];
                } else {
                    // Flip to bullish only when price clears the upper band
                    isBullish[i] = close > finalUpper[i];
                }
            }
        }

        int     lastI          = len - 1;
        int     prevI          = len - 2;
        boolean bullishNow     = isBullish[lastI];
        boolean bullishPrev    = isBullish[prevI];
        double  supertrendNow  = bullishNow  ? finalLower[lastI] : finalUpper[lastI];
        double  supertrendPrev = bullishPrev ? finalLower[prevI] : finalUpper[prevI];
        double  closeNow       = series.getBar(lastIdx).getClosePrice().doubleValue();
        double  atrNow         = atr.getValue(lastIdx).doubleValue();

        BigDecimal stVal      = bd(supertrendNow);
        BigDecimal atrVal     = bd(atrNow);
        BigDecimal priceVal   = bd(closeNow);
        BigDecimal distance   = closeNow > 0
                ? bd((closeNow - supertrendNow) / closeNow * 100.0) : null;

        log.info("[SUPERTREND] pair={} close={} supertrend={} bullish={} atr={}",
                pair, priceVal, stVal, bullishNow, atrVal);

        if (!bullishPrev && bullishNow) {
            String reason = String.format(
                    "Supertrend flipped BULLISH — close=%.2f above %.2f (ATR=%.4f)",
                    closeNow, supertrendPrev, atrNow);
            return new Signal(SignalType.BUY, BigDecimal.valueOf(78), reason,
                    pair, null, StrategyType.SUPERTREND, now, stVal, atrVal, distance, priceVal);
        }

        if (bullishPrev && !bullishNow) {
            String reason = String.format(
                    "Supertrend flipped BEARISH — close=%.2f below %.2f (ATR=%.4f)",
                    closeNow, supertrendPrev, atrNow);
            return new Signal(SignalType.SELL, BigDecimal.valueOf(74), reason,
                    pair, null, StrategyType.SUPERTREND, now, stVal, atrVal, distance, priceVal);
        }

        String trend  = bullishNow ? "BULLISH" : "BEARISH";
        String reason = String.format("Supertrend %s — close=%.2f vs %.2f (gap=%.2f%%)",
                trend, closeNow, supertrendNow,
                distance != null ? distance.doubleValue() : 0.0);
        return hold(reason, pair, now, stVal, atrVal, distance, priceVal);
    }

    private Signal hold(String reason, String pair, Instant evaluatedAt,
                        BigDecimal supertrend, BigDecimal atr, BigDecimal distance, BigDecimal price) {
        return new Signal(SignalType.HOLD, BigDecimal.valueOf(50), reason,
                pair, null, StrategyType.SUPERTREND, evaluatedAt, supertrend, atr, distance, price);
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }
}
