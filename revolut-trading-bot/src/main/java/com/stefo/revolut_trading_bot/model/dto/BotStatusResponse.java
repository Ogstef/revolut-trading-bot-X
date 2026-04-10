package com.stefo.revolut_trading_bot.model.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Top-level bot status — returned by GET /api/status.
 *
 * @param running            whether the bot is currently active (false = emergency stop engaged)
 * @param mode               PAPER or LIVE
 * @param pair               e.g. "BTC-EUR"
 * @param openPositions      count of currently open positions
 * @param dailyPnl           today's realised PnL in EUR
 * @param consecutiveLosses  current streak of consecutive losing trades
 * @param circuitBreakerOn   true if any circuit breaker is currently tripped
 * @param reportedAt         when this snapshot was taken
 */
public record BotStatusResponse(
        boolean running,
        String mode,
        String pair,
        long openPositions,
        BigDecimal dailyPnl,
        int consecutiveLosses,
        boolean circuitBreakerOn,
        Instant reportedAt
) {}
