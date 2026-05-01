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
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * RSI momentum / mean-reversion strategy.
 *
 * Uses RSI(14) crossings of the 30/70 thresholds to signal reversals:
 *   BUY  → RSI crosses above 30 (prev < 30, current ≥ 30)  — recovering from oversold
 *   SELL → RSI crosses above 70 (prev < 70, current ≥ 70)  — entering overbought
 *   HOLD → RSI stays between 30 and 70 (trending territory)
 *
 * <p>Regime filter (added 2026-04-29): mean-reversion strategies get crushed in
 * trending markets — see BACKTESTING.md §3.2 for the BTC/1h walk-forward where
 * window 2 (Jan-Mar trending regime) lost €42 with 8 of 12 trades stopped out.
 * BUY signals are now suppressed when ADX(14) ≥ {@link #ADX_TRENDING_THRESHOLD}
 * (default 25, the textbook "strong trend" cutoff). SELL signals always pass
 * through — we want to exit positions even when the market is trending against us.
 *
 * Signal field mapping (reusing Signal record fields):
 *   emaShort → null  (not used)
 *   emaLong  → ADX value (so the UI can surface why a BUY was suppressed)
 *   rsi      → current RSI value
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RsiMomentumStrategy implements TradingStrategy {

    private static final int    RSI_PERIOD              = 14;
    private static final int    ADX_PERIOD              = 14;
    private static final double OVERSOLD                = 30.0;
    private static final double OVERBOUGHT              = 70.0;
    /** ADX above this = strong trend = mean-reversion BUY suppressed. Conventional value. */
    private static final double ADX_TRENDING_THRESHOLD  = 25.0;

    /** ADX needs ~2× the period to stabilise; that's the binding constraint. */
    private static final int MIN_BARS = ADX_PERIOD * 2 + 1;

    private final TradingConfig config;

    @Override
    public StrategyType strategyType() {
        return StrategyType.RSI_MOMENTUM;
    }

    @Override
    public Signal evaluate(BarSeries series, String pair) {
        Instant now  = Instant.now();
        int lastIdx  = series.getEndIndex();

        if (lastIdx < MIN_BARS) {
            String reason = String.format("RSI_MOMENTUM: insufficient data — %d bars, need %d",
                    lastIdx + 1, MIN_BARS + 1);
            log.warn(reason);
            return hold(reason, pair, now, null, null, null);
        }

        ClosePriceIndicator close  = new ClosePriceIndicator(series);
        RSIIndicator        rsi    = new RSIIndicator(close, RSI_PERIOD);
        ADXIndicator        adx    = new ADXIndicator(series, ADX_PERIOD);

        int prevIdx = lastIdx - 1;

        double rsiNow  = rsi.getValue(lastIdx).doubleValue();
        double rsiPrev = rsi.getValue(prevIdx).doubleValue();
        double adxNow  = adx.getValue(lastIdx).doubleValue();
        BigDecimal priceNow = bd(close.getValue(lastIdx).doubleValue());
        BigDecimal rsiNowBd = bd(rsiNow);
        BigDecimal adxNowBd = bd(adxNow);

        log.info("[RSI_MOMENTUM] rsi={} prev={} adx={} price={}", rsiNow, rsiPrev, adxNow, priceNow);

        boolean recoveringFromOversold = rsiPrev < OVERSOLD && rsiNow >= OVERSOLD;
        boolean enteringOverbought     = rsiPrev < OVERBOUGHT && rsiNow >= OVERBOUGHT;

        // BUY: recovering from oversold, but only in non-trending regimes.
        if (recoveringFromOversold) {
            if (adxNow >= ADX_TRENDING_THRESHOLD) {
                String reason = String.format(
                        "Regime filter: trending market (ADX=%.1f ≥ %.0f) — RSI recovery from oversold suppressed (rsi=%.1f)",
                        adxNow, ADX_TRENDING_THRESHOLD, rsiNow);
                return hold(reason, pair, now, adxNowBd, rsiNowBd, priceNow);
            }
            String reason = String.format(
                    "RSI recovered from oversold — rsi=%.1f (was %.1f) ADX=%.1f (range)",
                    rsiNow, rsiPrev, adxNow);
            return new Signal(SignalType.BUY, BigDecimal.valueOf(73), reason,
                    pair, null, StrategyType.RSI_MOMENTUM, now, null, adxNowBd, rsiNowBd, priceNow);
        }

        // SELL passes through regardless of ADX — we always want to exit positions.
        if (enteringOverbought) {
            String reason = String.format("RSI entered overbought — rsi=%.1f (was %.1f, crossed %.0f) ADX=%.1f",
                    rsiNow, rsiPrev, OVERBOUGHT, adxNow);
            return new Signal(SignalType.SELL, BigDecimal.valueOf(69), reason,
                    pair, null, StrategyType.RSI_MOMENTUM, now, null, adxNowBd, rsiNowBd, priceNow);
        }

        String reason = String.format("RSI no threshold crossing — rsi=%.1f ADX=%.1f", rsiNow, adxNow);
        return hold(reason, pair, now, adxNowBd, rsiNowBd, priceNow);
    }

    private Signal hold(String reason, String pair, Instant evaluatedAt,
                        BigDecimal adxValue, BigDecimal rsiValue, BigDecimal price) {
        return new Signal(SignalType.HOLD, BigDecimal.valueOf(50), reason,
                pair, null, StrategyType.RSI_MOMENTUM, evaluatedAt, null, adxValue, rsiValue, price);
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }
}
