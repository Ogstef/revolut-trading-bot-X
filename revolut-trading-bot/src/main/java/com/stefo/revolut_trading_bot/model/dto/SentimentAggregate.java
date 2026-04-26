package com.stefo.revolut_trading_bot.model.dto;

import java.math.BigDecimal;

/**
 * Result of a windowed aggregation over {@code sentiment_snapshots}.
 *
 * Produced by {@link com.stefo.revolut_trading_bot.repository.SentimentSnapshotRepository}
 * via a JPQL constructor expression, so callers get a strongly-typed record — not an
 * {@code Object[]} array that needs index-unpacking.
 *
 * @param meanScore   volume-weighted mean score in [-1, +1]. {@code null} when window is empty.
 * @param totalVolume sum of {@code volume} over the window.
 * @param sampleSize  number of rows in the window.
 */
public record SentimentAggregate(
        BigDecimal meanScore,
        Long totalVolume,
        Long sampleSize
) {
    /** Convenience: empty window (no rows matched). */
    public static SentimentAggregate empty() {
        return new SentimentAggregate(null, 0L, 0L);
    }

    public boolean isEmpty() {
        return sampleSize == null || sampleSize == 0L;
    }
}
