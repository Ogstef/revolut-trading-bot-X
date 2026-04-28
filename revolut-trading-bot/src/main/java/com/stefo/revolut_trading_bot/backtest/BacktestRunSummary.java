package com.stefo.revolut_trading_bot.backtest;

import com.stefo.revolut_trading_bot.model.enums.StrategyType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight projection used by GET /api/backtest/runs (list view) — no trades or
 * equity curve to keep the payload small. The sidebar in the UI uses this shape.
 */
public record BacktestRunSummary(
        UUID id,
        String pair,
        StrategyType strategy,
        String interval,
        LocalDateTime startDate,
        LocalDateTime endDate,
        BigDecimal startingBalance,
        BacktestStats stats,
        String label,
        LocalDateTime createdAt
) {}
