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
 * Signal field mapping (reusing Signal record fields):
 *   emaShort → null  (not used)
 *   emaLong  → null  (not used)
 *   rsi      → current RSI value
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RsiMomentumStrategy implements TradingStrategy {

    private static final int    RSI_PERIOD    = 14;
    private static final double OVERSOLD      = 30.0;
    private static final double OVERBOUGHT    = 70.0;

    // Need RSI_PERIOD + 1 for prev bar
    private static final int MIN_BARS = RSI_PERIOD + 1;

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
            return hold(reason, pair, now, null, null);
        }

        ClosePriceIndicator close  = new ClosePriceIndicator(series);
        RSIIndicator        rsi    = new RSIIndicator(close, RSI_PERIOD);

        int prevIdx = lastIdx - 1;

        double rsiNow  = rsi.getValue(lastIdx).doubleValue();
        double rsiPrev = rsi.getValue(prevIdx).doubleValue();
        BigDecimal priceNow = bd(close.getValue(lastIdx).doubleValue());
        BigDecimal rsiNowBd = bd(rsiNow);

        log.info("[RSI_MOMENTUM] rsi={} prev={} price={}", rsiNow, rsiPrev, priceNow);

        // Recovering from oversold: RSI was below 30, now at-or-above 30
        if (rsiPrev < OVERSOLD && rsiNow >= OVERSOLD) {
            String reason = String.format("RSI recovered from oversold — rsi=%.1f (was %.1f, crossed %.0f)",
                    rsiNow, rsiPrev, OVERSOLD);
            return new Signal(SignalType.BUY, BigDecimal.valueOf(73), reason,
                    pair, StrategyType.RSI_MOMENTUM, now, null, null, rsiNowBd, priceNow);
        }

        // Entering overbought: RSI was below 70, now at-or-above 70
        if (rsiPrev < OVERBOUGHT && rsiNow >= OVERBOUGHT) {
            String reason = String.format("RSI entered overbought — rsi=%.1f (was %.1f, crossed %.0f)",
                    rsiNow, rsiPrev, OVERBOUGHT);
            return new Signal(SignalType.SELL, BigDecimal.valueOf(69), reason,
                    pair, StrategyType.RSI_MOMENTUM, now, null, null, rsiNowBd, priceNow);
        }

        String reason = String.format("RSI no threshold crossing — rsi=%.1f", rsiNow);
        return hold(reason, pair, now, rsiNowBd, priceNow);
    }

    private Signal hold(String reason, String pair, Instant evaluatedAt,
                        BigDecimal rsiValue, BigDecimal price) {
        return new Signal(SignalType.HOLD, BigDecimal.valueOf(50), reason,
                pair, StrategyType.RSI_MOMENTUM, evaluatedAt, null, null, rsiValue, price);
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }
}
