package com.stefo.revolut_trading_bot.strategy;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * EMA(9)/EMA(21) crossover strategy filtered by RSI(14).
 *
 * BUY  → EMA9 crosses above EMA21  AND  RSI between 30–70  AND  price above EMA21
 * SELL → EMA9 crosses below EMA21  OR   RSI > 70 (overbought)
 * HOLD → everything else
 *
 * Crossover is detected by comparing the current bar to the previous bar,
 * so we need at least emaLongPeriod + 1 bars before a real signal can fire.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmaCrossoverStrategy implements TradingStrategy {

    private final TradingConfig config;

    @Override
    public StrategyType strategyType() {
        return StrategyType.EMA_CROSSOVER;
    }

    @Override
    public Signal evaluate(BarSeries series, String pair) {
        int shortPeriod    = config.getStrategy().getEmaShortPeriod();   // 9
        int longPeriod     = config.getStrategy().getEmaLongPeriod();    // 21
        int rsiPeriod      = config.getStrategy().getRsiPeriod();        // 14
        int overbought     = config.getStrategy().getRsiOverbought();    // 70
        int oversold       = config.getStrategy().getRsiOversold();      // 30
        Instant now        = Instant.now();

        int lastIdx = series.getEndIndex();

        // Need prevIdx too, so minimum is longPeriod bars (0-indexed: lastIdx >= longPeriod)
        if (lastIdx < longPeriod) {
            String reason = String.format("Insufficient data: %d bars present, need at least %d",
                    lastIdx + 1, longPeriod + 1);
            log.warn(reason);
            return hold(reason, pair, now, null, null, null, null);
        }

        ClosePriceIndicator close  = new ClosePriceIndicator(series);
        EMAIndicator        ema9   = new EMAIndicator(close, shortPeriod);
        EMAIndicator        ema21  = new EMAIndicator(close, longPeriod);
        RSIIndicator        rsi    = new RSIIndicator(close, rsiPeriod);

        int prevIdx = lastIdx - 1;

        BigDecimal ema9Now   = bd(ema9.getValue(lastIdx).doubleValue());
        BigDecimal ema9Prev  = bd(ema9.getValue(prevIdx).doubleValue());
        BigDecimal ema21Now  = bd(ema21.getValue(lastIdx).doubleValue());
        BigDecimal ema21Prev = bd(ema21.getValue(prevIdx).doubleValue());
        BigDecimal rsiNow    = bd(rsi.getValue(lastIdx).doubleValue());
        BigDecimal priceNow  = bd(close.getValue(lastIdx).doubleValue());

        log.info("EMA{}={} EMA{}={} RSI={} price={}",
                shortPeriod, ema9Now, longPeriod, ema21Now, rsiNow, priceNow);

        // Crossover: EMA9 was at-or-below EMA21 yesterday, is above today → bullish
        boolean bullishCross  = ema9Prev.compareTo(ema21Prev) <= 0 && ema9Now.compareTo(ema21Now) > 0;
        // Crossover: EMA9 was at-or-above EMA21 yesterday, is below today → bearish
        boolean bearishCross  = ema9Prev.compareTo(ema21Prev) >= 0 && ema9Now.compareTo(ema21Now) < 0;
        boolean rsiNeutral    = rsiNow.doubleValue() >= oversold && rsiNow.doubleValue() <= overbought;
        boolean rsiIsOver     = rsiNow.doubleValue() > overbought;
        boolean aboveEma21    = priceNow.compareTo(ema21Now) > 0;

        if (bullishCross && rsiNeutral && aboveEma21) {
            String reason = String.format(
                    "EMA%d crossed above EMA%d — RSI=%.1f (neutral) — price above EMA%d",
                    shortPeriod, longPeriod, rsiNow.doubleValue(), longPeriod);
            return new Signal(SignalType.BUY, BigDecimal.valueOf(75), reason,
                    pair, StrategyType.EMA_CROSSOVER, now, ema9Now, ema21Now, rsiNow, priceNow);
        }

        if (bearishCross || rsiIsOver) {
            String reason = bearishCross
                    ? String.format("EMA%d crossed below EMA%d — RSI=%.1f",
                            shortPeriod, longPeriod, rsiNow.doubleValue())
                    : String.format("RSI=%.1f exceeded overbought threshold of %d",
                            rsiNow.doubleValue(), overbought);
            return new Signal(SignalType.SELL, BigDecimal.valueOf(70), reason,
                    pair, StrategyType.EMA_CROSSOVER, now, ema9Now, ema21Now, rsiNow, priceNow);
        }

        String reason = String.format("No crossover — EMA%d=%.2f EMA%d=%.2f RSI=%.1f",
                shortPeriod, ema9Now.doubleValue(), longPeriod, ema21Now.doubleValue(), rsiNow.doubleValue());
        return hold(reason, pair, now, ema9Now, ema21Now, rsiNow, priceNow);
    }

    private Signal hold(String reason, String pair, Instant evaluatedAt,
                        BigDecimal ema9, BigDecimal ema21, BigDecimal rsi, BigDecimal price) {
        return new Signal(SignalType.HOLD, BigDecimal.valueOf(50), reason,
                pair, StrategyType.EMA_CROSSOVER, evaluatedAt, ema9, ema21, rsi, price);
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }
}
