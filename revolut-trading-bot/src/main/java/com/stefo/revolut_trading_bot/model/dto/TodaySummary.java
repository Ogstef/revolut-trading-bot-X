package com.stefo.revolut_trading_bot.model.dto;

import com.stefo.revolut_trading_bot.model.entity.Trade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record TodaySummary(
        LocalDate date,
        LocalDateTime startOfDay,
        LocalDateTime generatedAt,

        int totalTrades,
        int winningTrades,
        int losingTrades,
        BigDecimal winRate,

        BigDecimal grossPnl,
        BigDecimal netPnl,
        BigDecimal totalFees,
        BigDecimal totalSlippage,
        BigDecimal feeDragPct,

        BigDecimal bestTrade,
        BigDecimal worstTrade,
        BigDecimal averageWin,
        BigDecimal averageLoss,
        BigDecimal expectancy,

        int positionsOpenedToday,
        int positionsClosedToday,
        int openPositionsNow,

        List<TodayTripleRow> byTriple,
        List<Trade> trades,
        List<PositionView> openPositions
) {
    public record TodayTripleRow(
            String pair,
            String interval,
            String strategy,
            String displayName,
            int totalTrades,
            int winningTrades,
            int losingTrades,
            BigDecimal grossPnl,
            BigDecimal netPnl,
            BigDecimal totalCosts
    ) {}
}
