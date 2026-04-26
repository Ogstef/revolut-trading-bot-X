package com.stefo.revolut_trading_bot.model.dto;

import com.stefo.revolut_trading_bot.model.enums.SentimentSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Public sentiment payload for {@code GET /api/market/sentiment}.
 *
 * {@link #subScores} is populated only when {@link #source} is {@link SentimentSource#COMBINED} —
 * exposes the per-source breakdown so the widget can render a COMBINED gauge plus per-source badges.
 */
public record SentimentResponse(
        String pair,                       // "BTC-EUR" / "ETH-EUR" / "SOL-EUR"
        SentimentSource source,            // REDDIT / CRYPTOPANIC / COMBINED
        String interval,                   // "15m" / "1h" / "4h" / "1d" / "1w" — the window used
        BigDecimal score,                  // volume-weighted mean in [-1, +1]; null when sample too small
        Long volume,                       // sum of row volumes in the window
        Long sampleSize,                   // row count in the window
        Instant capturedAt,                // when the aggregate was computed
        boolean stale,                     // true if no data hit the window
        List<SubScore> subScores           // non-null only for COMBINED; otherwise null
) {
    /** Per-source contribution when {@link #source} is {@link SentimentSource#COMBINED}. */
    public record SubScore(
            SentimentSource source,
            BigDecimal score,
            Long volume,
            Long sampleSize
    ) {}
}
