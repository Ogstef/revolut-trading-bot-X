package com.stefo.revolut_trading_bot.strategy.impl;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import org.ta4j.core.BarSeries;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Maps a ta4j {@link BarSeries} to one of the bot's canonical interval labels
 * ({@code "15m" / "1h" / "4h" / "1d" / "1w"}).
 *
 * Strategies that need interval-aware behaviour use this instead of modifying
 * the {@link com.stefo.revolut_trading_bot.strategy.TradingStrategy} interface
 * (which does not receive interval — {@code SignalEngine} injects it onto the
 * {@link com.stefo.revolut_trading_bot.strategy.Signal} after the fact).
 *
 * Primary path: {@code bar.getTimePeriod()} → {@link Duration} → minutes → label.
 * Fallback: delta between the end-times of the last two bars.
 * Neither works → {@link Optional#empty()} so callers can HOLD with a clear reason.
 */
public final class BarSeriesIntervalDetector {

    private BarSeriesIntervalDetector() {}

    public static Optional<String> labelFromSeries(BarSeries series) {
        if (series == null || series.getBarCount() == 0) {
            return Optional.empty();
        }

        final int endIdx = series.getEndIndex();

        // Primary: explicit Duration on the last bar.
        Duration period = series.getBar(endIdx).getTimePeriod();
        if (period != null && !period.isZero() && !period.isNegative()) {
            return Optional.of(TradingConfig.intervalLabel((int) period.toMinutes()));
        }

        // Fallback: delta between the last two bars' end times.
        if (endIdx >= 1) {
            ZonedDateTime last     = series.getBar(endIdx).getEndTime();
            ZonedDateTime previous = series.getBar(endIdx - 1).getEndTime();
            if (last != null && previous != null && last.isAfter(previous)) {
                long minutes = Duration.between(previous, last).toMinutes();
                if (minutes > 0) {
                    return Optional.of(TradingConfig.intervalLabel((int) minutes));
                }
            }
        }

        return Optional.empty();
    }
}
