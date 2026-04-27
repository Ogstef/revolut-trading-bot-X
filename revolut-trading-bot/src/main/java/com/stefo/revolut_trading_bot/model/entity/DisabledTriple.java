package com.stefo.revolut_trading_bot.model.entity;

import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One row per disabled (pair, strategy, interval) triple. Sparse —
 * absence of a row means the triple is enabled (the default).
 *
 * Loaded fully into memory at startup by TripleConfigService for O(1)
 * lookup on the trading-loop hot path (240 reads per cycle).
 */
@Entity
@Table(name = "disabled_triples", schema = "trading")
@IdClass(DisabledTripleId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisabledTriple {

    @Id
    @Column(nullable = false, length = 20)
    private String pair;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_name", nullable = false, length = 40)
    private StrategyType strategyName;

    @Id
    @Column(nullable = false, length = 10)
    private String interval;

    @Column(name = "disabled_at", nullable = false)
    private LocalDateTime disabledAt;

    @Column(length = 255)
    private String reason;

    @PrePersist
    void prePersist() {
        if (disabledAt == null) disabledAt = LocalDateTime.now();
    }
}
