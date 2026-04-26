package com.stefo.revolut_trading_bot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * All sentiment-pipeline configuration.
 *
 * Architecture: the Java backend is a receiver — a standalone scraper
 * microservice posts batches of Reddit posts and CryptoPanic news items to
 * {@code POST /api/sentiment/ingest/{source}}. This service validates, dedups,
 * classifies Reddit posts via Claude Haiku (within a €3/mo budget), derives
 * CryptoPanic scores from scraped vote counts, persists, aggregates, and
 * trades on the results.
 *
 * {@link #enabled} is an opt-in kill switch — when {@code false}, ingest
 * returns 503 and the three sentiment strategies return {@code HOLD}.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "sentiment")
public class SentimentConfig {

    /** Master kill switch. Default OFF so the pipeline is opt-in per environment. */
    private boolean enabled = false;

    @Valid @NotNull private Ingest          ingest       = new Ingest();
    @Valid @NotNull private RedditFilters   redditFilters = new RedditFilters();
    @Valid @NotNull private Classifier      classifier   = new Classifier();
    @Valid @NotNull private Strategies      strategies   = new Strategies();

    /** Keyed by interval label: "15m" / "1h" / "4h" / "1d" / "1w". */
    @NotNull
    private Map<String, Window> windows = new HashMap<>();

    // ─── Nested property classes ─────────────────────────────────────────────

    /** Ingest endpoint auth + limits. Shared secret between scraper and Java. */
    @Data
    public static class Ingest {
        /** Bearer token the scraper must present. Empty string disables the endpoint. */
        private String authToken = "";
        /** Max posts accepted per ingest request — rejects oversized batches with 413. */
        @Positive
        private int maxBatchSize = 200;
    }

    /** Applied server-side before a Reddit post is sent to the Haiku classifier. */
    @Data
    public static class RedditFilters {
        /** Upvotes required before a post is sent to the classifier. */
        @PositiveOrZero
        private int minScore = 5;
        /** Comments required before a post is sent to the classifier. */
        @PositiveOrZero
        private int minComments = 3;
        /** Skip posts older than this many hours at ingest time. */
        @Positive
        private int maxAgeHours = 24;
    }

    @Data
    public static class Classifier {
        private String anthropicApiKey = "";
        private String model           = "claude-haiku-4-5-20251001";
        @Positive
        private int batchSize          = 20;
        /** Hard monthly cap in USD. Classifier fails closed on breach. */
        @NotNull
        private BigDecimal monthlyBudgetUsd = new BigDecimal("3.00");
        /** Soft daily cap in USD. Monthly cap always wins. */
        @NotNull
        private BigDecimal dailyBudgetUsd   = new BigDecimal("0.12");
        /** Fire a one-shot Telegram alert once {@code usd_spent} crosses this % of the monthly cap. */
        @Positive
        private int alertAtMonthlyPct  = 80;
    }

    @Data
    public static class Window {
        /** Lookback window length (minutes). */
        @Positive
        private int minutes;
        /** Min Reddit sample below which {@code RedditSentimentStrategy} returns HOLD. */
        @Positive
        private int redditMinSample;
        /** Min CryptoPanic sample below which {@code CryptoPanicStrategy} returns HOLD. */
        @Positive
        private int cryptopanicMinSample;
        /** Min combined sample below which {@code CombinedSentimentStrategy} returns HOLD. */
        @Positive
        private int combinedMinSample;
    }

    @Data
    public static class Strategies {
        @Valid @NotNull private StrategyThresholds          reddit       = new StrategyThresholds();
        @Valid @NotNull private StrategyThresholds          cryptopanic  = new StrategyThresholds();
        @Valid @NotNull private CombinedStrategyThresholds  combined     = new CombinedStrategyThresholds();
    }

    /** Thresholds shared by REDDIT and CRYPTOPANIC strategies. */
    @Data
    public static class StrategyThresholds {
        @NotNull
        private BigDecimal buyThreshold  = new BigDecimal("0.35");
        @NotNull
        private BigDecimal sellThreshold = new BigDecimal("-0.35");
    }

    /** COMBINED strategy thresholds + extra agreement-filter flag. */
    @Data
    public static class CombinedStrategyThresholds {
        @NotNull
        private BigDecimal buyThreshold  = new BigDecimal("0.40");
        @NotNull
        private BigDecimal sellThreshold = new BigDecimal("-0.40");
        /** When true, HOLD if Reddit's and CryptoPanic's signs disagree even if the blend crosses threshold. */
        private boolean requireAgreement = true;
    }
}
