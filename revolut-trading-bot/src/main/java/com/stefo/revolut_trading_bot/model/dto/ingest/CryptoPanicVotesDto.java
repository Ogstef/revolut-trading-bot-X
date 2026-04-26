package com.stefo.revolut_trading_bot.model.dto.ingest;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * Vote counts scraped from a CryptoPanic news row. All fields default to 0 if absent.
 *
 * The derived score used by {@code CryptoPanicIngestService} is
 * {@code (positive - negative) / max(positive + negative, 1)} clamped to [-1, +1].
 */
public record CryptoPanicVotesDto(
        @PositiveOrZero int positive,
        @PositiveOrZero int negative,
        @PositiveOrZero int important,
        @PositiveOrZero int liked,
        @PositiveOrZero int disliked
) {
    public int totalEngagement() {
        int total = positive + negative + important + liked + disliked;
        return Math.max(total, 1);   // used as the snapshot's volume; never 0
    }
}
