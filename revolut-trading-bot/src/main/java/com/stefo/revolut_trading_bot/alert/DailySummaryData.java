package com.stefo.revolut_trading_bot.alert;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Aggregated daily summary data — built by DailySummaryScheduler, formatted by TelegramMessageFormatter.
 */
public record DailySummaryData(
        LocalDate date,
        int totalTradesClosed,
        int wins,
        int losses,
        BigDecimal winRate,
        // Gross P&L periods
        BigDecimal dailyPnl,
        BigDecimal weeklyPnl,
        BigDecimal allTimePnl,
        // Net P&L periods (after fees + slippage)
        BigDecimal dailyNetPnl,
        BigDecimal weeklyNetPnl,
        BigDecimal allTimeNetPnl,
        // Fee breakdown for today's closed trades
        BigDecimal dailyFees,
        BigDecimal dailySlippage,
        int openPositions,
        int circuitBreakersActive,
        List<TopMover> topWinners,
        List<TopMover> topLosers,
        String botMode
) {
    public record TopMover(String pair, String strategy, String interval,
                           BigDecimal grossPnl, BigDecimal netPnl) {}
}
