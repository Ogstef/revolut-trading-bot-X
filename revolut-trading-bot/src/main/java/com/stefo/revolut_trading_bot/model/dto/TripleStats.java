package com.stefo.revolut_trading_bot.model.dto;

import java.math.BigDecimal;

public record TripleStats(
        String pair,
        String interval,
        String strategy,
        String displayName,
        int totalTrades,
        int winningTrades,
        int losingTrades,
        BigDecimal winRate,
        BigDecimal totalPnl,
        BigDecimal averageWin,
        BigDecimal averageLoss,
        BigDecimal bestTrade,
        BigDecimal worstTrade,
        BigDecimal expectancy,
        int openPositions,
        boolean circuitBreakerActive,
        BigDecimal netPnl,
        BigDecimal totalCosts,
        BigDecimal feeDragPct,
        BigDecimal netExpectancy,
        boolean enabled
) {}
