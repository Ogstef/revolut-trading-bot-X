package com.stefo.revolut_trading_bot.model.entity;

import com.stefo.revolut_trading_bot.model.enums.OrderSide;
import com.stefo.revolut_trading_bot.model.enums.TradingMode;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "trades", schema = "trading")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "position_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Position position;

    @Column(nullable = false, length = 20)
    private String pair;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderSide side;

    @Column(name = "entry_price", nullable = false, precision = 18, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "exit_price", precision = 18, scale = 8)
    private BigDecimal exitPrice;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal quantity;

    @Column(precision = 18, scale = 8)
    private BigDecimal pnl;

    @Column(name = "pnl_pct", precision = 18, scale = 8)
    private BigDecimal pnlPct;

    @Column(name = "exit_reason", length = 20)
    private String exitReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "trading_mode", nullable = false, length = 10)
    @Builder.Default
    private TradingMode tradingMode = TradingMode.PAPER;

    @Column(name = "executed_at", nullable = false)
    @Builder.Default
    private LocalDateTime executedAt = LocalDateTime.now();
}
