package com.stefo.revolut_trading_bot.backtest;

import com.stefo.revolut_trading_bot.model.enums.StrategyType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full payload returned by POST /api/backtest/run and GET /api/backtest/runs/{id}.
 * Carries the equity curve and trade list — heavy, fetched only on demand.
 */
public record BacktestRunDetail(
        UUID id,
        String pair,
        StrategyType strategy,
        String interval,
        LocalDateTime startDate,
        LocalDateTime endDate,
        BigDecimal startingBalance,
        Map<String, Object> params,
        BacktestStats stats,
        List<SimulatedTrade> trades,
        List<EquityPoint> equityCurve,
        String label,
        String notes,
        LocalDateTime createdAt
) {}
