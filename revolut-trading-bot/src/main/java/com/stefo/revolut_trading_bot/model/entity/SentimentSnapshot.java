package com.stefo.revolut_trading_bot.model.entity;

import com.stefo.revolut_trading_bot.model.enums.SentimentSource;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One scored post or news item.
 *
 * Rows come from two sources: Reddit (score from Claude Haiku classifier) and
 * CryptoPanic (score derived from community votes). Aggregates are computed
 * in-memory by {@code SentimentService.scoreFor(pair, interval, source)} over a
 * time window matched to the interval.
 *
 * Uniqueness is enforced on {@code (source, external_id)} so re-scraping never
 * duplicates the same post.
 */
@Entity
@Table(name = "sentiment_snapshots", schema = "trading")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SentimentSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** "BTC-EUR" / "ETH-EUR" / "SOL-EUR". "ALL" when no pair-specific keyword matched. */
    @Column(nullable = false, length = 20)
    private String pair;

    /** REDDIT or CRYPTOPANIC. Never {@link SentimentSource#COMBINED} — that source is computed, not persisted. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SentimentSource source;

    /** Reddit post id (e.g. "t3_abc123") or CryptoPanic post id. */
    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    /** Classified score in [-1.000, +1.000]. Negative = bearish, positive = bullish. */
    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal score;

    /** Engagement weight for the row — upvotes for Reddit, total votes for CryptoPanic. Minimum 1. */
    @Column(nullable = false)
    @Builder.Default
    private Integer volume = 1;

    /** Always 1 for today's scraper (one post per row). Kept for forward-compat with summarized batches. */
    @Column(name = "sample_size", nullable = false)
    @Builder.Default
    private Integer sampleSize = 1;

    /** Optional Jackson-serialized metadata (title, subreddit, vote breakdown, etc.). Mirrors BotEvent's pattern. */
    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @PrePersist
    void prePersist() {
        if (capturedAt == null) capturedAt = LocalDateTime.now();
        if (volume == null)     volume     = 1;
        if (sampleSize == null) sampleSize = 1;
    }
}
