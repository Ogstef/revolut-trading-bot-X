package com.stefo.revolut_trading_bot.strategy.impl;

import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.strategy.Signal;
import com.stefo.revolut_trading_bot.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.StochasticRSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Stochastic RSI strategy — RSI applied to RSI.
 *
 * Faster and more sensitive than plain RSI; catches short-term mean reversions
 * that the standard RSI misses. Values range 0.0–1.0 internally (multiplied
 * by 100 for human-readable Signal fields).
 *
 * Signals:
 *   BUY  → StochRSI crosses above 0.20 (prev < 0.20, current ≥ 0.20) — recovering from oversold
 *   SELL → StochRSI crosses above 0.80 (prev < 0.80, current ≥ 0.80) — entering overbought
 *   HOLD → StochRSI stays between the thresholds
 *
 * Signal field mapping:
 *   emaShort → StochRSI × 100 (0–100 scale for display)
 *   emaLong  → null
 *   rsi      → underlying RSI(14) value
 */
@Slf4j
@Component
public class StochRsiStrategy implements TradingStrategy {

    private static final int    RSI_PERIOD      = 14;
    private static final int    STOCH_PERIOD    = 14;
    private static final double OVERSOLD_BTC    = 0.20;
    private static final double OVERSOLD_ALT    = 0.10;
    private static final double OVERBOUGHT_BTC  = 0.80;
    private static final double OVERBOUGHT_ALT  = 0.90;

    private static double oversold(String pair) {
        return "BTC-EUR".equals(pair) ? OVERSOLD_BTC : OVERSOLD_ALT;
    }

    private static double overbought(String pair) {
        return "BTC-EUR".equals(pair) ? OVERBOUGHT_BTC : OVERBOUGHT_ALT;
    }

    // Need RSI_PERIOD + STOCH_PERIOD bars + 1 for prev
    private static final int MIN_BARS = RSI_PERIOD + STOCH_PERIOD + 1;

    @Override
    public StrategyType strategyType() {
        return StrategyType.STOCH_RSI;
    }

    @Override
    public Signal evaluate(BarSeries series, String pair) {
        Instant now    = Instant.now();
        int lastIdx    = series.getEndIndex();

        if (lastIdx < MIN_BARS) {
            String reason = String.format("STOCH_RSI: insufficient data — %d bars, need %d",
                    lastIdx + 1, MIN_BARS + 1);
            log.warn(reason);
            return hold(reason, pair, now, null, null);
        }

        ClosePriceIndicator   close    = new ClosePriceIndicator(series);
        RSIIndicator          rsi      = new RSIIndicator(close, RSI_PERIOD);
        StochasticRSIIndicator stochRsi = new StochasticRSIIndicator(rsi, STOCH_PERIOD);

        int prevIdx = lastIdx - 1;

        double stochNow  = stochRsi.getValue(lastIdx).doubleValue();
        double stochPrev = stochRsi.getValue(prevIdx).doubleValue();
        double rsiNow    = rsi.getValue(lastIdx).doubleValue();
        double priceNow  = close.getValue(lastIdx).doubleValue();

        BigDecimal stochDisplay = bd(stochNow * 100);
        BigDecimal rsiDisplay   = bd(rsiNow);
        BigDecimal price        = bd(priceNow);

        log.info("[STOCH_RSI] stoch={} prev={} rsi={} price={}", stochDisplay, bd(stochPrev * 100), rsiDisplay, price);

        // Recovering from oversold
        if (stochPrev < oversold(pair) && stochNow >= oversold(pair)) {
            String reason = String.format("StochRSI crossed above %.0f — stoch=%.1f (was %.1f)",
                    oversold(pair) * 100, stochNow * 100, stochPrev * 100);
            return new Signal(SignalType.BUY, BigDecimal.valueOf(74), reason,
                    pair, null, StrategyType.STOCH_RSI, now, stochDisplay, null, rsiDisplay, price);
        }

        // Entering overbought
        if (stochPrev < overbought(pair) && stochNow >= overbought(pair)) {
            String reason = String.format("StochRSI crossed above %.0f — stoch=%.1f (was %.1f)",
                    overbought(pair) * 100, stochNow * 100, stochPrev * 100);
            return new Signal(SignalType.SELL, BigDecimal.valueOf(70), reason,
                    pair, null, StrategyType.STOCH_RSI, now, stochDisplay, null, rsiDisplay, price);
        }

        String reason = String.format("StochRSI no threshold crossing — stoch=%.1f", stochNow * 100);
        return hold(reason, pair, now, stochDisplay, price);
    }

    private Signal hold(String reason, String pair, Instant evaluatedAt,
                        BigDecimal stochDisplay, BigDecimal price) {
        return new Signal(SignalType.HOLD, BigDecimal.valueOf(50), reason,
                pair, null, StrategyType.STOCH_RSI, evaluatedAt, stochDisplay, null, null, price);
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }
}
