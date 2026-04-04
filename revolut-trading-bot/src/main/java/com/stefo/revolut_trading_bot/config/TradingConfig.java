package com.stefo.revolut_trading_bot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Data
@Validated
@ConfigurationProperties(prefix = "trading")
public class TradingConfig {

    @NotNull
    private String pair;

    @NotNull
    private String mode;

    @Positive
    private int pollingIntervalSeconds;

    @Valid
    @NotNull
    private Strategy strategy = new Strategy();

    @Valid
    @NotNull
    private Risk risk = new Risk();

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
