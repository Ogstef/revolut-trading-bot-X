package com.stefo.revolut_trading_bot.strategy.impl;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.strategy.Signal;
import com.stefo.revolut_trading_bot.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Bollinger Bands mean-reversion strategy.
 *
 * Indicators:
 *   - Middle band = SMA(20) of close
 *   - σ           = StandardDeviation(close, 20)
 *   - Upper band  = SMA(20) + 2σ
 *   - Lower band  = SMA(20) − 2σ
 *
 * Signals:
 *   BUY  → price crosses ABOVE the lower band (was ≤ lower, now > lower) — bounce off oversold
 *   SELL → price crosses ABOVE the upper band (was ≤ upper, now > upper) — entering overbought
 *   HOLD → price stays inside the bands
 *
 * Signal field mapping (reusing Signal record fields):
 *   emaShort → upper band value
 *   emaLong  → lower band value
 *   rsi      → %B = (price − lower) / (upper − lower) × 100
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BollingerBandsStrategy implements TradingStrategy {

    private static final int    PERIOD = 20;
    private static final double MULT   = 2.0;

    // Need PERIOD bars + 1 for the previous bar
    private static final int MIN_BARS = PERIOD + 1;

    private final TradingConfig config;

    @Override
    public StrategyType strategyType() {
        return StrategyType.BOLLINGER;
    }

    @Override
    public Signal evaluate(BarSeries series, String pair) {
        Instant now  = Instant.now();
        int lastIdx  = series.getEndIndex();

        if (lastIdx < MIN_BARS) {
            String reason = String.format("BOLLINGER: insufficient data — %d bars, need %d",
                    lastIdx + 1, MIN_BARS + 1);
            log.warn(reason);
            return hold(reason, pair, now, null, null, null, null);
        }

        ClosePriceIndicator         close  = new ClosePriceIndicator(series);
        SMAIndicator                sma    = new SMAIndicator(close, PERIOD);
        StandardDeviationIndicator  stdDev = new StandardDeviationIndicator(close, PERIOD);

        int prevIdx = lastIdx - 1;

        double smaNow   = sma.getValue(lastIdx).doubleValue();
        double stdNow   = stdDev.getValue(lastIdx).doubleValue();
        double upperNow = smaNow + MULT * stdNow;
        double lowerNow = smaNow - MULT * stdNow;

        double smaPrev   = sma.getValue(prevIdx).doubleValue();
        double stdPrev   = stdDev.getValue(prevIdx).doubleValue();
        double upperPrev = smaPrev + MULT * stdPrev;
        double lowerPrev = smaPrev - MULT * stdPrev;

        double priceNow  = close.getValue(lastIdx).doubleValue();
        double pricePrev = close.getValue(prevIdx).doubleValue();

        BigDecimal upper   = bd(upperNow);
        BigDecimal lower   = bd(lowerNow);
        BigDecimal price   = bd(priceNow);

        // %B: where price sits within the band (0 = at lower, 100 = at upper)
        BigDecimal bandWidth = upper.subtract(lower);
        BigDecimal percentB  = bandWidth.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.valueOf(50)
                : price.subtract(lower)
                        .divide(bandWidth, 8, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);

        log.info("[BOLLINGER] upper={} lower={} price={} %B={}", upper, lower, price, percentB);

        // Bounce off lower band: was at-or-below, now above → buy
        if (pricePrev <= lowerPrev && priceNow > lowerNow) {
            String reason = String.format("Price crossed above lower band — price=%.2f lower=%.2f %%B=%.1f",
                    priceNow, lowerNow, percentB.doubleValue());
            return new Signal(SignalType.BUY, BigDecimal.valueOf(70), reason,
                    pair, null, StrategyType.BOLLINGER, now, upper, lower, percentB, price);
        }

        // Breakout above upper band: was at-or-below, now above → sell (overbought)
        if (pricePrev <= upperPrev && priceNow > upperNow) {
            String reason = String.format("Price crossed above upper band — price=%.2f upper=%.2f %%B=%.1f",
                    priceNow, upperNow, percentB.doubleValue());
            return new Signal(SignalType.SELL, BigDecimal.valueOf(65), reason,
                    pair, null, StrategyType.BOLLINGER, now, upper, lower, percentB, price);
        }

        String reason = String.format("Price inside bands — price=%.2f upper=%.2f lower=%.2f %%B=%.1f",
                priceNow, upperNow, lowerNow, percentB.doubleValue());
        return hold(reason, pair, now, upper, lower, percentB, price);
    }

    private Signal hold(String reason, String pair, Instant evaluatedAt,
                        BigDecimal upper, BigDecimal lower, BigDecimal percentB, BigDecimal price) {
        return new Signal(SignalType.HOLD, BigDecimal.valueOf(50), reason,
                pair, null, StrategyType.BOLLINGER, evaluatedAt, upper, lower, percentB, price);
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }
}
