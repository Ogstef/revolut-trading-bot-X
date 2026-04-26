package com.stefo.revolut_trading_bot.service;

import com.stefo.revolut_trading_bot.config.SentimentConfig;
import com.stefo.revolut_trading_bot.config.SentimentConfig.Window;
import com.stefo.revolut_trading_bot.model.dto.SentimentAggregate;
import com.stefo.revolut_trading_bot.model.dto.SentimentScore;
import com.stefo.revolut_trading_bot.model.enums.SentimentSource;
import com.stefo.revolut_trading_bot.repository.SentimentSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public API over {@code sentiment_snapshots}. Three strategies call
 * {@link #scoreFor(String, String, SentimentSource)} every signal cycle; each
 * result is cached for {@value #CACHE_TTL_SECONDS}s per
 * {@code (pair, interval, source)} to keep DB load flat.
 *
 * For {@link SentimentSource#COMBINED}, the score is a sample-weighted blend of
 * the REDDIT and CRYPTOPANIC aggregates:
 *
 * <pre>
 *   combined = (reddit.score * reddit.volume + cp.score * cp.volume) / (reddit.volume + cp.volume)
 * </pre>
 *
 * The individual sub-scores are attached as {@code components} so
 * {@code CombinedSentimentStrategy} can apply its {@code require-agreement} filter.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SentimentService {

    private static final long CACHE_TTL_SECONDS = 60;

    private final SentimentConfig config;
    private final SentimentSnapshotRepository repository;

    /** UTC clock — overridable in tests. */
    private final Clock clock = Clock.systemUTC();

    private final Map<CacheKey, CachedScore> cache = new ConcurrentHashMap<>();

    // ─── Public API ──────────────────────────────────────────────────────

    /**
     * Returns the sentiment aggregate for the given slice. Never {@code null}:
     * an empty-window or insufficient-sample case returns a {@link SentimentScore}
     * with {@code score=null} and {@code hasSignal() == false}.
     */
    public SentimentScore scoreFor(String pair, String interval, SentimentSource source) {
        CacheKey key = new CacheKey(pair, interval, source);
        CachedScore cached = cache.get(key);
        Instant now = Instant.now(clock);
        if (cached != null && !cached.isExpired(now)) {
            return cached.score();
        }

        SentimentScore fresh = computeFresh(pair, interval, source, now);
        cache.put(key, new CachedScore(fresh, now));
        return fresh;
    }

    /** Drop the full in-memory cache. Call after config reloads or from tests. */
    public void invalidateCache() {
        cache.clear();
    }

    // ─── Computation ─────────────────────────────────────────────────────

    private SentimentScore computeFresh(String pair, String interval,
                                        SentimentSource source, Instant now) {
        Window window = config.getWindows().get(interval);
        if (window == null) {
            log.warn("[sentiment] no window configured for interval '{}'; returning empty score", interval);
            return new SentimentScore(pair, interval, source, null, 0, 0, 0, 0, now, List.of());
        }

        return source == SentimentSource.COMBINED
                ? computeCombined(pair, interval, window, now)
                : computeSingleSource(pair, interval, source, window, now);
    }

    @Transactional(readOnly = true)
    protected SentimentScore computeSingleSource(String pair, String interval,
                                                 SentimentSource source, Window window, Instant now) {
        int minSample = minSampleFor(source, window);
        SentimentAggregate agg = queryAggregate(pair, source, window, now);
        return buildSingleSourceScore(pair, interval, source, window, now, agg, minSample);
    }

    @Transactional(readOnly = true)
    protected SentimentScore computeCombined(String pair, String interval,
                                             Window window, Instant now) {
        SentimentAggregate reddit = queryAggregate(pair, SentimentSource.REDDIT, window, now);
        SentimentAggregate crypto = queryAggregate(pair, SentimentSource.CRYPTOPANIC, window, now);

        SentimentScore redditScore = buildSingleSourceScore(
                pair, interval, SentimentSource.REDDIT, window, now, reddit, window.getRedditMinSample());
        SentimentScore cryptoScore = buildSingleSourceScore(
                pair, interval, SentimentSource.CRYPTOPANIC, window, now, crypto, window.getCryptopanicMinSample());

        long redditVol = redditScore.hasSignal() ? redditScore.volume() : 0L;
        long cryptoVol = cryptoScore.hasSignal() ? cryptoScore.volume() : 0L;
        long totalVol  = redditVol + cryptoVol;
        long totalN    = safeSampleSize(reddit) + safeSampleSize(crypto);

        BigDecimal blended = null;
        if (totalVol > 0) {
            BigDecimal redditWeighted = redditScore.hasSignal()
                    ? redditScore.score().multiply(BigDecimal.valueOf(redditVol))
                    : BigDecimal.ZERO;
            BigDecimal cryptoWeighted = cryptoScore.hasSignal()
                    ? cryptoScore.score().multiply(BigDecimal.valueOf(cryptoVol))
                    : BigDecimal.ZERO;
            blended = redditWeighted.add(cryptoWeighted)
                    .divide(BigDecimal.valueOf(totalVol), 3, RoundingMode.HALF_UP);
        }

        int combinedMin = window.getCombinedMinSample();
        boolean sufficient = totalN >= combinedMin && blended != null;

        return new SentimentScore(
                pair,
                interval,
                SentimentSource.COMBINED,
                sufficient ? blended : null,
                totalVol,
                totalN,
                combinedMin,
                window.getMinutes(),
                now,
                List.of(redditScore, cryptoScore)
        );
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private SentimentAggregate queryAggregate(String pair, SentimentSource source,
                                              Window window, Instant now) {
        LocalDateTime since = now.minus(Duration.ofMinutes(window.getMinutes()))
                .atZone(clock.getZone())
                .toLocalDateTime();
        SentimentAggregate agg = repository.aggregate(pair, source, since);
        return agg != null ? agg : SentimentAggregate.empty();
    }

    private SentimentScore buildSingleSourceScore(String pair, String interval,
                                                  SentimentSource source, Window window, Instant now,
                                                  SentimentAggregate agg, int minSample) {
        long sampleSize = safeSampleSize(agg);
        long volume = agg.totalVolume() != null ? agg.totalVolume() : 0L;
        BigDecimal rawScore = agg.meanScore();
        BigDecimal score = (rawScore != null && sampleSize >= minSample)
                ? rawScore.setScale(3, RoundingMode.HALF_UP)
                : null;

        return new SentimentScore(
                pair, interval, source, score,
                volume, sampleSize, minSample, window.getMinutes(), now,
                List.of()
        );
    }

    private int minSampleFor(SentimentSource source, Window window) {
        return switch (source) {
            case REDDIT      -> window.getRedditMinSample();
            case CRYPTOPANIC -> window.getCryptopanicMinSample();
            case COMBINED    -> window.getCombinedMinSample();
        };
    }

    private static long safeSampleSize(SentimentAggregate agg) {
        return agg == null || agg.sampleSize() == null ? 0L : agg.sampleSize();
    }

    // ─── Internals ───────────────────────────────────────────────────────

    private record CacheKey(String pair, String interval, SentimentSource source) {}

    private record CachedScore(SentimentScore score, Instant computedAt) {
        boolean isExpired(Instant now) {
            return Duration.between(computedAt, now).getSeconds() >= CACHE_TTL_SECONDS;
        }
    }
}
