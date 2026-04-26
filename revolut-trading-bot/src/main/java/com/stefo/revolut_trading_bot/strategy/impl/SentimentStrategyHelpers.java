package com.stefo.revolut_trading_bot.strategy.impl;

import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.strategy.Signal;
import org.ta4j.core.BarSeries;

import java.math.BigDecimal;
import java.time.Instant;

/** Shared HOLD-signal builders and last-close extraction for the three sentiment strategies. */
final class SentimentStrategyHelpers {

    private SentimentStrategyHelpers() {}

    static BigDecimal lastClose(BarSeries series) {
        if (series == null || series.getBarCount() == 0) return BigDecimal.ZERO;
        double close = series.getBar(series.getEndIndex()).getClosePrice().doubleValue();
        return BigDecimal.valueOf(close);
    }

    static Signal disabled(StrategyType type, String pair, BigDecimal price, Instant now) {
        return new Signal(
                SignalType.HOLD,
                BigDecimal.valueOf(50),
                "sentiment pipeline disabled",
                pair, null, type, now,
                null, null, null, price
        );
    }

    static Signal missingInterval(StrategyType type, String pair, BigDecimal price, Instant now) {
        return new Signal(
                SignalType.HOLD,
                BigDecimal.valueOf(50),
                "unable to derive interval from BarSeries",
                pair, null, type, now,
                null, null, null, price
        );
    }
}
