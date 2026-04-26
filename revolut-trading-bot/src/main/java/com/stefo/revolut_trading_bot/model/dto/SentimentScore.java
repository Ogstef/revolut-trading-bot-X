package com.stefo.revolut_trading_bot.model.dto;

import com.stefo.revolut_trading_bot.model.enums.SentimentSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Computed sentiment aggregate for one {@code (pair, interval, source)} slice.
 *
 * Returned by {@code SentimentService.scoreFor(...)}. Strongly typed so strategies
 * never unpack maps or arrays — every field is a named, typed value.
 *
 * {@link #components} is populated only when {@link #source} is {@link SentimentSource#COMBINED};
 * it exposes the underlying Reddit + CryptoPanic sub-scores so the strategy can
 * apply an agreement filter. For per-source scores ({@code REDDIT} / {@code CRYPTOPANIC}),
 * {@link #components} is an empty list.
 *
 * {@link #score} is {@code null} when {@link #sampleSize} is below {@link #minSample}.
 * Callers must check {@link #hasSignal()} before trading on it.
 *
 * @param pair        e.g. "BTC-EUR"
 * @param interval    e.g. "1h"
 * @param source      {@link SentimentSource#REDDIT}, {@link SentimentSource#CRYPTOPANIC}, or {@link SentimentSource#COMBINED}
 * @param score       volume-weighted mean in [-1, +1], {@code null} when insufficient sample
 * @param volume      sum of row volumes (engagement weights) over the window
 * @param sampleSize  count of rows in the window
 * @param minSample   the {@code sample-min} threshold this slice was evaluated against
 * @param windowMinutes  window length used, in minutes
 * @param computedAt  when this aggregate was computed (used for cache invalidation)
 * @param components  sub-scores, populated only when {@code source == COMBINED}
 */
public record SentimentScore(
        String pair,
        String interval,
        SentimentSource source,
        BigDecimal score,
        long volume,
        long sampleSize,
        int minSample,
        int windowMinutes,
        Instant computedAt,
        List<SentimentScore> components
) {
    /** True when the sample is large enough and a score is present. */
    public boolean hasSignal() {
        return score != null && sampleSize >= minSample;
    }

    /** True when the blended score is above the given buy threshold. Requires {@link #hasSignal()}. */
    public boolean isBullish(BigDecimal buyThreshold) {
        return hasSignal() && score.compareTo(buyThreshold) > 0;
    }

    /** True when the blended score is below the given sell threshold. Requires {@link #hasSignal()}. */
    public boolean isBearish(BigDecimal sellThreshold) {
        return hasSignal() && score.compareTo(sellThreshold) < 0;
    }

    /**
     * For {@link SentimentSource#COMBINED}: returns true iff Reddit and CryptoPanic
     * both have signals and their signs agree. Returns true for non-COMBINED sources
     * (no agreement concept applies).
     */
    public boolean componentsAgree() {
        if (source != SentimentSource.COMBINED) return true;
        if (components.size() < 2) return false;
        SentimentScore r  = findComponent(SentimentSource.REDDIT);
        SentimentScore cp = findComponent(SentimentSource.CRYPTOPANIC);
        if (r == null || cp == null || !r.hasSignal() || !cp.hasSignal()) return false;
        return r.score.signum() == cp.score.signum();
    }

    public SentimentScore findComponent(SentimentSource s) {
        return components.stream().filter(c -> c.source() == s).findFirst().orElse(null);
    }
}
