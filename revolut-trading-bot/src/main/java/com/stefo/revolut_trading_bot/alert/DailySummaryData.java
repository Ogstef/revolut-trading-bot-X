package com.stefo.revolut_trading_bot.alert;

import com.stefo.revolut_trading_bot.model.enums.TradingVehicle;

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
        String botMode,
        // Per-vehicle leverage aggregates for today (empty when no leveraged activity)
        List<VehicleAggregate> leverageOverview
) {
    public record TopMover(String pair, String strategy, String interval,
                           BigDecimal grossPnl, BigDecimal netPnl) {}

    public record VehicleAggregate(TradingVehicle vehicle,
                                   int trades,
                                   int wins,
                                   int losses,
                                   int liquidations,
                                   BigDecimal grossPnl,
                                   BigDecimal netPnl) {}
}
