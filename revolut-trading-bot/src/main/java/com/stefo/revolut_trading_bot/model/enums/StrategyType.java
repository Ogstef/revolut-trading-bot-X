package com.stefo.revolut_trading_bot.model.enums;

/**
 * Canonical identifier for every trading strategy in the system.
 *
 * Using an enum (rather than a plain String) means:
 *   - the compiler catches typos at build time
 *   - IDE auto-complete works on strategy names everywhere
 *   - JPA stores the name() value ("EMA_CROSSOVER" etc.) via @Enumerated(EnumType.STRING)
 *   - Spring Boot @ConfigurationProperties converts YAML keys to enum values automatically
 *
 * Add a new constant here whenever a new strategy class is created.
 */
public enum StrategyType {

    EMA_CROSSOVER("EMA Crossover"),
    MACD("MACD"),
    BOLLINGER("Bollinger Bands"),
    RSI_MOMENTUM("RSI Momentum"),
    // Tier 1
    STOCH_RSI("Stochastic RSI"),
    TRIPLE_EMA("Triple EMA"),
    PARABOLIC_SAR("Parabolic SAR"),
    // Tier 2
    ADX_DI("ADX + Directional Index"),
    CCI("CCI"),
    // Phase 10
    MFI("Money Flow Index"),
    DONCHIAN("Donchian Breakout"),
    ICHIMOKU("Ichimoku Cloud"),
    SUPERTREND("Supertrend"),
    // Phase 14 — sentiment (news + social)
    REDDIT_SENTIMENT("Reddit Sentiment"),
    CRYPTOPANIC_SENTIMENT("CryptoPanic Sentiment"),
    COMBINED_SENTIMENT("Combined Sentiment");

    /** Human-readable label used in logs and API responses. */
    private final String displayName;

    StrategyType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
