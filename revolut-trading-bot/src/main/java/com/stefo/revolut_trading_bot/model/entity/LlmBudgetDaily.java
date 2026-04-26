package com.stefo.revolut_trading_bot.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Persistent tally of Claude Haiku spend, one row per UTC day.
 *
 * Survives process restarts — the classifier reads the current day's row before
 * every call and aborts if the monthly sum would exceed the hard cap.
 */
@Entity
@Table(name = "llm_budget_daily", schema = "trading")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmBudgetDaily {

    /** UTC day this row covers — primary key, one row per day. */
    @Id
    @Column(nullable = false)
    private LocalDate day;

    /** USD spent on this day. Precision matches column DECIMAL(8,4). */
    @Column(name = "usd_spent", nullable = false, precision = 8, scale = 4)
    @Builder.Default
    private BigDecimal usdSpent = BigDecimal.ZERO;

    @Column(name = "posts_classified", nullable = false)
    @Builder.Default
    private Integer postsClassified = 0;

    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;

    @PrePersist
    @PreUpdate
    void touch() {
        lastUpdated = LocalDateTime.now();
        if (usdSpent == null)        usdSpent = BigDecimal.ZERO;
        if (postsClassified == null) postsClassified = 0;
    }
}
