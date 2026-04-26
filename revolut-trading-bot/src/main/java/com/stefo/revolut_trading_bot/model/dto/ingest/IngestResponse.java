package com.stefo.revolut_trading_bot.model.dto.ingest;

/**
 * Per-batch ingest outcome. Returned from both ingest endpoints so the
 * scraper can log observable counts rather than guessing what happened.
 *
 * Invariant: {@code received == accepted + deduped + filtered}.
 * {@code classified} is a subset of {@code accepted}, relevant for Reddit only
 * (for CryptoPanic it is always 0 — scores come from scraped votes, no LLM call).
 */
public record IngestResponse(
        int received,
        int accepted,
        int deduped,
        int filtered,
        int classified
) {
    public static IngestResponse empty() {
        return new IngestResponse(0, 0, 0, 0, 0);
    }
}
