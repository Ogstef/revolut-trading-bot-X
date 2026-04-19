package com.stefo.revolut_trading_bot.portfolio;

import java.math.BigDecimal;

/**
 * Aggregated statistics across all closed trades.
 *
 * winRate       = winningTrades / totalTrades × 100
 * averageWin    = average gross PnL of profitable trades
 * averageLoss   = average gross PnL of losing trades (negative value)
 * expectancy    = (winRate × averageWin) + ((1 - winRate) × averageLoss)
 * netPnl        = totalPnl minus all fees and slippage
 * feeDragPct    = 100 × (totalFees + totalSlippage) / |totalPnl|
 * netExpectancy = expectancy computed on netPnl basis
 */
public record TradingStats(
        int totalTrades,
        int winningTrades,
        int losingTrades,
        BigDecimal winRate,        // 0–100
        BigDecimal totalPnl,       // gross
        BigDecimal averageWin,
        BigDecimal averageLoss,    // negative
        BigDecimal bestTrade,
        BigDecimal worstTrade,
        BigDecimal expectancy,
        BigDecimal netPnl,
        BigDecimal totalFees,
        BigDecimal totalSlippage,
        BigDecimal feeDragPct,
        BigDecimal netExpectancy
) {}
