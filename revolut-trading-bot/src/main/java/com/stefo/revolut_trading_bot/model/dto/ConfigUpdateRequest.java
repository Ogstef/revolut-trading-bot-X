package com.stefo.revolut_trading_bot.model.dto;

import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Runtime config overrides — sent to POST /api/config.
 *
 * All fields are optional. Only non-null fields are applied — null = leave unchanged.
 * Changes take effect on the next trading cycle without a restart.
 */
public record ConfigUpdateRequest(

        // Risk parameters
        @Positive BigDecimal maxPositionPct,
        @Positive Integer maxConcurrentPositions,
        @Positive BigDecimal maxDailyLossPct,
        @Positive Integer maxConsecutiveLosses,
        @Positive BigDecimal takeProfitPct,
        @Positive BigDecimal stopLossPct,

        // Strategy parameters
        @Positive Integer emaShortPeriod,
        @Positive Integer emaLongPeriod,
        @Positive Integer rsiPeriod,
        @Positive Integer rsiOverbought,
        @Positive Integer rsiOversold,

        // Paper balance (PAPER mode only)
        @Positive BigDecimal paperBalance
) {}
