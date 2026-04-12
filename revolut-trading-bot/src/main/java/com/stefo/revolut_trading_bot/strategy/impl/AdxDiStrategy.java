package com.stefo.revolut_trading_bot.strategy.impl;

import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.strategy.Signal;
import com.stefo.revolut_trading_bot.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * ADX + Directional Index strategy — the only strategy that measures TREND STRENGTH.
 *
 * ADX tells you HOW STRONG a trend is, not which direction. The +DI/-DI crossover
 * tells you direction. Together they filter out choppy sideways markets — exactly
 * where all oscillator strategies get whipsawed.
 *
 * Indicators:
 *   ADXIndicator(series, 14)      — trend strength 0–100 (>25 = strong, <20 = weak/choppy)
 *   PlusDIIndicator(series, 14)   — bullish directional movement
 *   MinusDIIndicator(series, 14)  — bearish directional movement
 *
 * Signals:
 *   BUY  → +DI crosses above -DI AND ADX > 25 (strong uptrend confirmed)
 *   SELL → -DI crosses above +DI AND ADX > 25 (strong downtrend confirmed)
 *   HOLD → ADX < 20 (no clear trend) OR no DI crossover
 *
 * Signal field mapping:
 *   emaShort → +DI value
 *   emaLong  → -DI value
 *   rsi      → ADX value (show prominently — it's the key number)
 */
@Slf4j
@Component
public class AdxDiStrategy implements TradingStrategy {

    private static final int    PERIOD          = 14;
    private static final double ADX_STRONG      = 25.0;   // trend is strong enough to trade
    private static final double ADX_WEAK        = 20.0;   // below this = choppy, sit out

    // ADX needs ~2× the period to stabilise
    private static final int MIN_BARS = PERIOD * 2 + 1;

    @Override
    public StrategyType strategyType() {
        return StrategyType.ADX_DI;
    }

    @Override
    public Signal evaluate(BarSeries series, String pair) {
        Instant now  = Instant.now();
        int lastIdx  = series.getEndIndex();

        if (lastIdx < MIN_BARS) {
            String reason = String.format("ADX_DI: insufficient data — %d bars, need %d",
                    lastIdx + 1, MIN_BARS + 1);
            log.warn(reason);
            return hold(reason, pair, now, null, null, null, null);
        }

        ADXIndicator    adx     = new ADXIndicator(series, PERIOD);
        PlusDIIndicator plusDi  = new PlusDIIndicator(series, PERIOD);
        MinusDIIndicator minusDi = new MinusDIIndicator(series, PERIOD);

        int prevIdx = lastIdx - 1;

        double adxNow      = adx.getValue(lastIdx).doubleValue();
        double plusNow     = plusDi.getValue(lastIdx).doubleValue();
        double minusNow    = minusDi.getValue(lastIdx).doubleValue();
        double plusPrev    = plusDi.getValue(prevIdx).doubleValue();
        double minusPrev   = minusDi.getValue(prevIdx).doubleValue();

        BigDecimal adxVal   = bd(adxNow);
        BigDecimal plusVal  = bd(plusNow);
        BigDecimal minusVal = bd(minusNow);

        log.info("[ADX_DI] adx={} +DI={} -DI={} | +DI prev={} -DI prev={}",
                adxVal, plusVal, minusVal, bd(plusPrev), bd(minusPrev));

        // BUY: +DI crosses above -DI AND ADX is strong
        if (plusPrev <= minusPrev && plusNow > minusNow && adxNow > ADX_STRONG) {
            String reason = String.format("+DI crossed above -DI with ADX=%.1f (strong trend) — +DI=%.1f -DI=%.1f",
                    adxNow, plusNow, minusNow);
            return new Signal(SignalType.BUY, BigDecimal.valueOf(78), reason,
                    pair, StrategyType.ADX_DI, now, plusVal, minusVal, adxVal, null);
        }

        // SELL: -DI crosses above +DI AND ADX is strong
        if (minusPrev <= plusPrev && minusNow > plusNow && adxNow > ADX_STRONG) {
            String reason = String.format("-DI crossed above +DI with ADX=%.1f (strong trend) — +DI=%.1f -DI=%.1f",
                    adxNow, plusNow, minusNow);
            return new Signal(SignalType.SELL, BigDecimal.valueOf(74), reason,
                    pair, StrategyType.ADX_DI, now, plusVal, minusVal, adxVal, null);
        }

        // Determine reason for hold
        String reason;
        if (adxNow < ADX_WEAK) {
            reason = String.format("Choppy market — ADX=%.1f (below %.0f, no trend)", adxNow, ADX_WEAK);
        } else if (adxNow < ADX_STRONG) {
            reason = String.format("Weak trend — ADX=%.1f (below %.0f threshold) — no DI crossover",
                    adxNow, ADX_STRONG);
        } else {
            reason = String.format("No DI crossover — ADX=%.1f +DI=%.1f -DI=%.1f", adxNow, plusNow, minusNow);
        }
        return hold(reason, pair, now, plusVal, minusVal, adxVal, null);
    }

    private Signal hold(String reason, String pair, Instant evaluatedAt,
                        BigDecimal plusDi, BigDecimal minusDi, BigDecimal adx, BigDecimal price) {
        return new Signal(SignalType.HOLD, BigDecimal.valueOf(50), reason,
                pair, StrategyType.ADX_DI, evaluatedAt, plusDi, minusDi, adx, price);
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }
}
