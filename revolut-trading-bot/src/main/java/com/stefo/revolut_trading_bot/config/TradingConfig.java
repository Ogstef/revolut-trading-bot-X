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

    @NotNull
    private String mode;

    @Positive
    private int pollingIntervalSeconds;

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

    /**
     * The first configured pair — used by legacy single-pair endpoints (/api/status, /api/trades, etc.)
     * and as a fallback when no pair context is available.
     */
    public String primaryPair() {
        return pairs.isEmpty() ? "BTC-EUR" : pairs.get(0);
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
