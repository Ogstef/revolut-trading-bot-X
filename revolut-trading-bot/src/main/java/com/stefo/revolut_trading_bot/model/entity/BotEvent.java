package com.stefo.revolut_trading_bot.model.entity;

import com.stefo.revolut_trading_bot.model.enums.BotEventSeverity;
import com.stefo.revolut_trading_bot.model.enums.BotEventType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "bot_events", schema = "trading")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BotEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private BotEventType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BotEventSeverity severity;

    @Column(length = 20)
    private String pair;

    @Column(name = "interval", length = 10)
    private String interval;

    @Column(length = 40)
    private String strategy;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
