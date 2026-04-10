package com.stefo.revolut_trading_bot.portfolio;

import java.math.BigDecimal;

/**
 * Aggregated statistics across all closed trades.
 *
 * winRate       = winningTrades / totalTrades × 100
 * averageWin    = average PnL of profitable trades
 * averageLoss   = average PnL of losing trades (negative value)
 * expectancy    = (winRate × averageWin) + ((1 - winRate) × averageLoss)
 *                 Positive expectancy means the strategy is profitable over time.
 */
public record TradingStats(
        int totalTrades,
        int winningTrades,
        int losingTrades,
        BigDecimal winRate,        // 0–100
        BigDecimal totalPnl,
        BigDecimal averageWin,
        BigDecimal averageLoss,    // negative
        BigDecimal bestTrade,
        BigDecimal worstTrade,
        BigDecimal expectancy
) {}
