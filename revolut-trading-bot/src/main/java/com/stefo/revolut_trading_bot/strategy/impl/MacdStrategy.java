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
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * MACD histogram crossover strategy.
 *
 * Indicators:
 *   - MACD line   = EMA(12) − EMA(26) of close
 *   - Signal line = EMA(9) of the MACD line
 *   - Histogram   = MACD line − signal line
 *
 * Signals:
 *   BUY  → histogram crosses above zero (prev ≤ 0, current > 0)  — bullish momentum
 *   SELL → histogram crosses below zero (prev ≥ 0, current < 0)  — bearish momentum
 *   HOLD → histogram stays on same side of zero
 *
 * Signal field mapping (reusing Signal record fields):
 *   emaShort → MACD line value
 *   emaLong  → signal line value
 *   rsi      → histogram value
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MacdStrategy implements TradingStrategy {

    private static final int FAST_PERIOD   = 12;
    private static final int SLOW_PERIOD   = 26;
    private static final int SIGNAL_PERIOD = 9;

    // Minimum bars: slow EMA needs 26, signal EMA needs 9 more on top → 35 + 1 for prev
    private static final int MIN_BARS = SLOW_PERIOD + SIGNAL_PERIOD + 1;

    private final TradingConfig config;

    @Override
    public StrategyType strategyType() {
        return StrategyType.MACD;
    }

    @Override
    public Signal evaluate(BarSeries series, String pair) {
        Instant now  = Instant.now();
        int lastIdx  = series.getEndIndex();

        if (lastIdx < MIN_BARS) {
            String reason = String.format("MACD: insufficient data — %d bars present, need %d",
                    lastIdx + 1, MIN_BARS + 1);
            log.warn(reason);
            return hold(reason, pair, now, null, null, null, null);
        }

        ClosePriceIndicator close  = new ClosePriceIndicator(series);
        MACDIndicator       macd   = new MACDIndicator(close, FAST_PERIOD, SLOW_PERIOD);
        EMAIndicator        signal = new EMAIndicator(macd, SIGNAL_PERIOD);

        int prevIdx = lastIdx - 1;

        BigDecimal macdNow      = bd(macd.getValue(lastIdx).doubleValue());
        BigDecimal signalNow    = bd(signal.getValue(lastIdx).doubleValue());
        BigDecimal histNow      = macdNow.subtract(signalNow);

        BigDecimal macdPrev     = bd(macd.getValue(prevIdx).doubleValue());
        BigDecimal signalPrev   = bd(signal.getValue(prevIdx).doubleValue());
        BigDecimal histPrev     = macdPrev.subtract(signalPrev);

        BigDecimal priceNow     = bd(close.getValue(lastIdx).doubleValue());

        log.info("[MACD] macd={} signal={} hist={} price={}", macdNow, signalNow, histNow, priceNow);

        // Bullish crossover: histogram was ≤ 0, now > 0
        if (histPrev.compareTo(BigDecimal.ZERO) <= 0 && histNow.compareTo(BigDecimal.ZERO) > 0) {
            String reason = String.format("MACD histogram crossed above zero — hist=%.6f macd=%.2f signal=%.2f",
                    histNow.doubleValue(), macdNow.doubleValue(), signalNow.doubleValue());
            return new Signal(SignalType.BUY, BigDecimal.valueOf(72), reason,
                    pair, null, StrategyType.MACD, now, macdNow, signalNow, histNow, priceNow);
        }

        // Bearish crossover: histogram was ≥ 0, now < 0
        if (histPrev.compareTo(BigDecimal.ZERO) >= 0 && histNow.compareTo(BigDecimal.ZERO) < 0) {
            String reason = String.format("MACD histogram crossed below zero — hist=%.6f macd=%.2f signal=%.2f",
                    histNow.doubleValue(), macdNow.doubleValue(), signalNow.doubleValue());
            return new Signal(SignalType.SELL, BigDecimal.valueOf(68), reason,
                    pair, null, StrategyType.MACD, now, macdNow, signalNow, histNow, priceNow);
        }

        String reason = String.format("MACD no crossover — hist=%.6f (side=%s)",
                histNow.doubleValue(), histNow.compareTo(BigDecimal.ZERO) > 0 ? "bullish" : "bearish");
        return hold(reason, pair, now, macdNow, signalNow, histNow, priceNow);
    }

    private Signal hold(String reason, String pair, Instant evaluatedAt,
                        BigDecimal macdLine, BigDecimal signalLine, BigDecimal histogram,
                        BigDecimal price) {
        return new Signal(SignalType.HOLD, BigDecimal.valueOf(50), reason,
                pair, null, StrategyType.MACD, evaluatedAt, macdLine, signalLine, histogram, price);
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }
}
