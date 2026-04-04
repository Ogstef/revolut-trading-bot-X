package com.stefo.revolut_trading_bot.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "candlesticks", schema = "trading",
        uniqueConstraints = @UniqueConstraint(columnNames = {"pair", "interval", "timestamp"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Candlestick {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String pair;

    @Column(name = "interval", nullable = false, length = 10)
    private String interval;

    @Column(name = "open_price", nullable = false, precision = 18, scale = 8)
    private BigDecimal openPrice;

    @Column(name = "high_price", nullable = false, precision = 18, scale = 8)
    private BigDecimal highPrice;

    @Column(name = "low_price", nullable = false, precision = 18, scale = 8)
    private BigDecimal lowPrice;

    @Column(name = "close_price", nullable = false, precision = 18, scale = 8)
    private BigDecimal closePrice;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal volume;

    @Column(nullable = false)
    private LocalDateTime timestamp;
}
