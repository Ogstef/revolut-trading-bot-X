package com.stefo.revolut_trading_bot.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.stefo.revolut_trading_bot.model.enums.OrderSide;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.model.enums.TradingMode;
import com.stefo.revolut_trading_bot.model.enums.TradingVehicle;
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
    @JsonIgnore
    private Position position;

    @Column(nullable = false, length = 20)
    private String pair;

    @Column(name = "interval", nullable = false, length = 10)
    @Builder.Default
    private String interval = "15m";

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

    @Column(name = "entry_fee", nullable = false, precision = 18, scale = 8)
    @Builder.Default
    private BigDecimal entryFee = BigDecimal.ZERO;

    @Column(name = "exit_fee", nullable = false, precision = 18, scale = 8)
    @Builder.Default
    private BigDecimal exitFee = BigDecimal.ZERO;

    @Column(name = "entry_slippage", nullable = false, precision = 18, scale = 8)
    @Builder.Default
    private BigDecimal entrySlippage = BigDecimal.ZERO;

    @Column(name = "exit_slippage", nullable = false, precision = 18, scale = 8)
    @Builder.Default
    private BigDecimal exitSlippage = BigDecimal.ZERO;

    @Column(name = "net_pnl", precision = 18, scale = 8)
    private BigDecimal netPnl;

    @Column(name = "net_pnl_pct", precision = 18, scale = 8)
    private BigDecimal netPnlPct;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_name", nullable = false, length = 50)
    private StrategyType strategyName;

    @Column(name = "exit_reason", length = 20)
    private String exitReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "trading_mode", nullable = false, length = 10)
    @Builder.Default
    private TradingMode tradingMode = TradingMode.PAPER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private TradingVehicle vehicle = TradingVehicle.SPOT;

    @Column(nullable = false)
    @Builder.Default
    private short leverage = 1;

    @Column(precision = 18, scale = 8)
    private BigDecimal collateral;

    @Column(name = "funding_fees", nullable = false, precision = 18, scale = 8)
    @Builder.Default
    private BigDecimal fundingFees = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private boolean liquidated = false;

    @Column(name = "executed_at", nullable = false)
    @Builder.Default
    private LocalDateTime executedAt = LocalDateTime.now();

    // Null while the position is open; set by PaperTradingService.closePosition()
    @Column(name = "closed_at")
    private LocalDateTime closedAt;
}
