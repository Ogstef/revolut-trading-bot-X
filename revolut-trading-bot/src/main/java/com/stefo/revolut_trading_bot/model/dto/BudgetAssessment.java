package com.stefo.revolut_trading_bot.model.dto;

import java.math.BigDecimal;

/**
 * Result of a pre-spend budget check from {@code LlmBudgetTracker}.
 *
 * Strongly typed so the classifier can log exactly which cap blocked a call
 * instead of unpacking a generic boolean.
 */
public record BudgetAssessment(
        BudgetDecision decision,
        BigDecimal dailySpentUsd,
        BigDecimal monthlySpentUsd,
        BigDecimal dailyCapUsd,
        BigDecimal monthlyCapUsd
) {
    public boolean allowed() {
        return decision == BudgetDecision.ALLOWED;
    }

    public enum BudgetDecision {
        ALLOWED,
        BLOCKED_DAILY_CAP,
        BLOCKED_MONTHLY_CAP
    }
}
