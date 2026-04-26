package com.stefo.revolut_trading_bot.model.enums;

/**
 * Origin of a sentiment score.
 *
 * {@link #REDDIT} and {@link #CRYPTOPANIC} are persisted on {@code sentiment_snapshots} rows.
 * {@link #COMBINED} is a computed source — never stored, only read from by
 * {@code CombinedSentimentStrategy} as a sample-weighted blend of the other two.
 */
public enum SentimentSource {

    REDDIT("Reddit"),
    CRYPTOPANIC("CryptoPanic"),
    COMBINED("Combined");

    private final String displayName;

    SentimentSource(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
