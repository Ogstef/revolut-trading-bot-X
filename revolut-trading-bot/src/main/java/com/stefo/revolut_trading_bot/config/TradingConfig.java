package com.stefo.revolut_trading_bot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.stefo.revolut_trading_bot.model.enums.StrategyType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Validated
@ConfigurationProperties(prefix = "trading")
public class TradingConfig {

    // All active trading pairs — the first entry is the "primary" pair used for legacy
    // single-pair endpoints like GET /api/status.
    @NotEmpty
    private List<String> pairs = new ArrayList<>();

    // Candle intervals in minutes (e.g. [15, 60] for 15m and 1h).
    // The first entry is the "primary" interval used when ?interval= is omitted.
    @NotEmpty
    private List<Integer> intervals = new ArrayList<>(List.of(15));

    @NotNull
    private String mode;

    @Positive
    private int pollingIntervalSeconds;

    // Number of days of signal_logs history to retain. Older rows are deleted nightly by SignalLogCleanupScheduler.
    @Positive
    private int signalLogRetentionDays = 14;

    // Fallback balance used when a (pair, strategy) combination is not listed in strategyBalances.
    @Positive
    private BigDecimal paperBalance;

    // The strategy whose signal is used for the legacy /api/status metrics.
    // All strategies still run and record signals independently regardless of this value.
    private StrategyType primaryStrategy = StrategyType.EMA_CROSSOVER;

    // Per-(pair, strategy) virtual paper balances.
    // Outer key = pair string (e.g. "BTC-EUR"), inner key = StrategyType enum.
    // Spring Boot @ConfigurationProperties converts YAML keys to enum values automatically.
    // Falls back to trading.paper-balance when a (pair, strategy) entry is absent.
    private Map<String, Map<StrategyType, BigDecimal>> strategyBalances = new HashMap<>();

    @Valid
    @NotNull
    private Strategy strategy = new Strategy();

    @Valid
    @NotNull
    private Risk risk = new Risk();

    @Valid
    @NotNull
    private Costs costs = new Costs();

    /**
     * The first configured pair — used by legacy single-pair endpoints (/api/status, /api/trades, etc.)
     * and as a fallback when no pair context is available.
     */
    public String primaryPair() {
        return pairs.isEmpty() ? "BTC-EUR" : pairs.get(0);
    }

    /**
     * The label of the first configured interval (e.g. "15m").
     * Used as default when ?interval= query param is omitted.
     */
    public String primaryInterval() {
        return intervals.isEmpty() ? "15m" : intervalLabel(intervals.get(0));
    }

    /**
     * Returns all configured interval labels (e.g. ["15m", "1h"]).
     */
    public List<String> intervalLabels() {
        return intervals.stream().map(TradingConfig::intervalLabel).toList();
    }

    /**
     * Converts interval minutes to a human-readable label.
     * 15 -> "15m", 60 -> "1h", 240 -> "4h", 1440 -> "1d", 10080 -> "1w"
     */
    public static String intervalLabel(int minutes) {
        if (minutes >= 10080 && minutes % 10080 == 0) {
            return (minutes / 10080) + "w";
        }
        if (minutes >= 1440 && minutes % 1440 == 0) {
            return (minutes / 1440) + "d";
        }
        if (minutes >= 60 && minutes % 60 == 0) {
            return (minutes / 60) + "h";
        }
        return minutes + "m";
    }

    /**
     * Converts an interval label back to minutes.
     * "15m" -> 15, "1h" -> 60, "4h" -> 240, "1d" -> 1440, "1w" -> 10080
     */
    public static int intervalMinutes(String label) {
        if (label.endsWith("w")) {
            return Integer.parseInt(label.substring(0, label.length() - 1)) * 10080;
        }
        if (label.endsWith("d")) {
            return Integer.parseInt(label.substring(0, label.length() - 1)) * 1440;
        }
        if (label.endsWith("h")) {
            return Integer.parseInt(label.substring(0, label.length() - 1)) * 60;
        }
        if (label.endsWith("m")) {
            return Integer.parseInt(label.substring(0, label.length() - 1));
        }
        return Integer.parseInt(label);
    }

    /**
     * Returns a display-friendly interval name (e.g. "15 min", "1 hour", "4 hours", "1 day", "1 week").
     */
    public static String intervalDisplayName(int minutes) {
        if (minutes >= 10080 && minutes % 10080 == 0) {
            int weeks = minutes / 10080;
            return weeks + (weeks == 1 ? " week" : " weeks");
        }
        if (minutes >= 1440 && minutes % 1440 == 0) {
            int days = minutes / 1440;
            return days + (days == 1 ? " day" : " days");
        }
        if (minutes >= 60 && minutes % 60 == 0) {
            int hours = minutes / 60;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        return minutes + " min";
    }

    @Data
    public static class Strategy {
        @Positive
        private int emaShortPeriod;

        @Positive
        private int emaLongPeriod;

        @Positive
        private int rsiPeriod;

        @Positive
        private int rsiOverbought;

        @Positive
        private int rsiOversold;
    }

    @Data
    public static class Costs {
        @NotNull
        private BigDecimal feeRate = BigDecimal.ZERO;

        @NotNull
        private BigDecimal slippageRate = BigDecimal.ZERO;
    }

    @Data
    public static class Risk {
        @Positive
        private BigDecimal maxPositionPct;

        @Positive
        private int maxConcurrentPositions;

        @Positive
        private BigDecimal maxDailyLossPct;

        @Positive
        private int maxConsecutiveLosses;

        @Positive
        private BigDecimal takeProfitPct;

        @Positive
        private BigDecimal stopLossPct;
    }
}
