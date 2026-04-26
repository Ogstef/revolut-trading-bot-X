package com.stefo.revolut_trading_bot.repository;

import com.stefo.revolut_trading_bot.model.dto.SentimentAggregate;
import com.stefo.revolut_trading_bot.model.entity.SentimentSnapshot;
import com.stefo.revolut_trading_bot.model.enums.SentimentSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface SentimentSnapshotRepository extends JpaRepository<SentimentSnapshot, Long> {

    /** Dedup guard for scrapers — skip re-classifying posts we've already stored. */
    boolean existsBySourceAndExternalId(SentimentSource source, String externalId);

    Optional<SentimentSnapshot> findBySourceAndExternalId(SentimentSource source, String externalId);

    /**
     * Volume-weighted aggregate for a (pair, source) over {@code [since, now)}.
     * Returned as a typed {@link SentimentAggregate} — no {@code Object[]} unwrapping.
     *
     * Pair matching uses equality OR {@code 'ALL'} so posts with no pair-keyword hit
     * still contribute to global sentiment. Callers that want strict pair-only
     * aggregates should use {@link #aggregateStrict(String, SentimentSource, LocalDateTime)}.
     */
    @Query("""
            SELECT new com.stefo.revolut_trading_bot.model.dto.SentimentAggregate(
                       CAST(SUM(s.score * s.volume) / NULLIF(SUM(s.volume), 0) AS java.math.BigDecimal),
                       CAST(COALESCE(SUM(s.volume), 0)         AS java.lang.Long),
                       CAST(COUNT(s.id)                         AS java.lang.Long))
              FROM SentimentSnapshot s
             WHERE s.source     = :source
               AND s.capturedAt >= :since
               AND (s.pair = :pair OR s.pair = 'ALL')
            """)
    SentimentAggregate aggregate(@Param("pair")   String pair,
                                 @Param("source") SentimentSource source,
                                 @Param("since")  LocalDateTime since);

    @Query("""
            SELECT new com.stefo.revolut_trading_bot.model.dto.SentimentAggregate(
                       CAST(SUM(s.score * s.volume) / NULLIF(SUM(s.volume), 0) AS java.math.BigDecimal),
                       CAST(COALESCE(SUM(s.volume), 0)         AS java.lang.Long),
                       CAST(COUNT(s.id)                         AS java.lang.Long))
              FROM SentimentSnapshot s
             WHERE s.source     = :source
               AND s.capturedAt >= :since
               AND s.pair       = :pair
            """)
    SentimentAggregate aggregateStrict(@Param("pair")   String pair,
                                       @Param("source") SentimentSource source,
                                       @Param("since")  LocalDateTime since);
}
