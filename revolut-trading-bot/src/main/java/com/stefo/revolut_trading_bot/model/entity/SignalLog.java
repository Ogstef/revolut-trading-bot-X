package com.stefo.revolut_trading_bot.model.entity;

import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "signal_logs", schema = "trading")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String pair;

    @Column(name = "interval", nullable = false, length = 10)
    @Builder.Default
    private String interval = "15m";

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, length = 10)
    private SignalType signalType;

    @Column(precision = 5, scale = 2)
    private BigDecimal confidence;

    private String reason;

    @Column(name = "ema_short", precision = 18, scale = 8)
    private BigDecimal emaShort;

    @Column(name = "ema_long", precision = 18, scale = 8)
    private BigDecimal emaLong;

    @Column(precision = 18, scale = 8)
    private BigDecimal rsi;

    @Column(name = "current_price", precision = 18, scale = 8)
    private BigDecimal currentPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_name", nullable = false, length = 50)
    private StrategyType strategyName;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
