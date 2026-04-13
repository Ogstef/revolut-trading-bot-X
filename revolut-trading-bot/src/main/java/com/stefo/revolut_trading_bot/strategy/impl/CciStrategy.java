package com.stefo.revolut_trading_bot.strategy.impl;

import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.strategy.Signal;
import com.stefo.revolut_trading_bot.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.CCIIndicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * CCI (Commodity Channel Index) strategy.
 *
 * Measures deviation of price from its statistical mean. A different oscillator
 * from RSI — better at identifying cyclical turning points because it uses
 * typical price (high+low+close)/3 and normalises against mean deviation.
 * Typical range: −200 to +200. Overbought > +100, oversold < −100.
 *
 * Indicators: CCIIndicator(series, 20)
 *
 * Signals:
 *   BUY  → CCI crosses above −100 (prev < −100, current ≥ −100) — recovering from oversold
 *   SELL → CCI crosses above +100 (prev < +100, current ≥ +100) — entering overbought
 *   HOLD → CCI between −100 and +100 (neutral zone)
 *
 * Signal field mapping:
 *   emaShort → null
 *   emaLong  → null
 *   rsi      → CCI value (can be outside 0–100 — display as-is)
 */
@Slf4j
@Component
public class CciStrategy implements TradingStrategy {

    private static final int    PERIOD    = 20;
    private static final double OVERSOLD  = -100.0;
    private static final double OVERBOUGHT = 100.0;

    private static final int MIN_BARS = PERIOD;

    @Override
    public StrategyType strategyType() {
        return StrategyType.CCI;
    }

    @Override
    public Signal evaluate(BarSeries series, String pair) {
        Instant now  = Instant.now();
        int lastIdx  = series.getEndIndex();

        if (lastIdx < MIN_BARS) {
            String reason = String.format("CCI: insufficient data — %d bars, need %d",
                    lastIdx + 1, MIN_BARS + 1);
            log.warn(reason);
            return hold(reason, pair, now, null, null);
        }

        CCIIndicator cci = new CCIIndicator(series, PERIOD);

        int prevIdx = lastIdx - 1;

        double cciNow  = cci.getValue(lastIdx).doubleValue();
        double cciPrev = cci.getValue(prevIdx).doubleValue();
        // CCI indicator doesn't expose a close price directly — derive it from close price indicator
        double priceNow = series.getBar(lastIdx).getClosePrice().doubleValue();

        BigDecimal cciVal   = bd(cciNow);
        BigDecimal priceVal = bd(priceNow);

        log.info("[CCI] cci={} prev={} price={}", cciVal, bd(cciPrev), priceVal);

        // Recovering from oversold: CCI was below -100, now at-or-above -100
        if (cciPrev < OVERSOLD && cciNow >= OVERSOLD) {
            String reason = String.format("CCI recovered from oversold — cci=%.1f (was %.1f, crossed %.0f)",
                    cciNow, cciPrev, OVERSOLD);
            return new Signal(SignalType.BUY, BigDecimal.valueOf(71), reason,
                    pair, null, StrategyType.CCI, now, null, null, cciVal, priceVal);
        }

        // Entering overbought: CCI was below +100, now at-or-above +100
        if (cciPrev < OVERBOUGHT && cciNow >= OVERBOUGHT) {
            String reason = String.format("CCI entered overbought — cci=%.1f (was %.1f, crossed %.0f)",
                    cciNow, cciPrev, OVERBOUGHT);
            return new Signal(SignalType.SELL, BigDecimal.valueOf(67), reason,
                    pair, null, StrategyType.CCI, now, null, null, cciVal, priceVal);
        }

        String zone   = cciNow < OVERSOLD ? "oversold" : cciNow > OVERBOUGHT ? "overbought" : "neutral";
        String reason = String.format("CCI no threshold crossing — cci=%.1f (%s)", cciNow, zone);
        return hold(reason, pair, now, cciVal, priceVal);
    }

    private Signal hold(String reason, String pair, Instant evaluatedAt,
                        BigDecimal cciVal, BigDecimal price) {
        return new Signal(SignalType.HOLD, BigDecimal.valueOf(50), reason,
                pair, null, StrategyType.CCI, evaluatedAt, null, null, cciVal, price);
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }
}
