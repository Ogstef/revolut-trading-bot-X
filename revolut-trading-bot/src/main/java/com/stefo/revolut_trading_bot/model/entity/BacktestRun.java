package com.stefo.revolut_trading_bot.model.entity;

import com.stefo.revolut_trading_bot.backtest.BacktestStats;
import com.stefo.revolut_trading_bot.backtest.EquityPoint;
import com.stefo.revolut_trading_bot.backtest.SimulatedTrade;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persisted backtest run. JSONB columns mapped via @JdbcTypeCode(SqlTypes.JSON);
 * Hibernate uses the configured Jackson ObjectMapper to (de)serialize the typed
 * fields — including Java records.
 */
@Entity
@Table(name = "backtest_runs", schema = "trading")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestRun {

    @Id
    private UUID id;

    @Column(nullable = false, length = 20)
    private String pair;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private StrategyType strategy;

    @Column(nullable = false, length = 10)
    private String interval;

    @Column(name = "start_date", nullable = false)
    private LocalDateTime startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDateTime endDate;

    @Column(name = "starting_balance", nullable = false, precision = 18, scale = 8)
    private BigDecimal startingBalance;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> params;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private BacktestStats stats;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<SimulatedTrade> trades;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "equity_curve", nullable = false, columnDefinition = "jsonb")
    private List<EquityPoint> equityCurve;

    @Column(length = 120)
    private String label;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
