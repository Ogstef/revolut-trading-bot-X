package com.stefo.revolut_trading_bot.backtest;

import java.math.BigDecimal;

/**
 * Aggregated statistics for a backtest run. Combines the 15 fields from the live
 * portfolio.TradingStats with 9 risk/quality metrics that only make sense for a
 * full historical replay (Sharpe, max drawdown, profit factor, t-statistic, etc.).
 *
 * All numeric fields are BigDecimal for consistency with TradingStats. Computed
 * by BacktestStatsCalculator from a List of SimulatedTrade + EquityPoint.
 */
public record BacktestStats(
        // Base TradingStats (15 fields)
        int totalTrades,
        int winningTrades,
        int losingTrades,
        BigDecimal winRate,           // 0–100
        BigDecimal totalPnl,          // gross
        BigDecimal averageWin,
        BigDecimal averageLoss,       // negative
        BigDecimal bestTrade,
        BigDecimal worstTrade,
        BigDecimal expectancy,        // gross expectancy / trade
        BigDecimal netPnl,
        BigDecimal totalFees,
        BigDecimal totalSlippage,
        BigDecimal feeDragPct,
        BigDecimal netExpectancy,

        // Backtest-specific risk + quality metrics (9 fields)
        BigDecimal sharpeRatio,
        BigDecimal maxDrawdown,
        BigDecimal maxDrawdownPct,
        int        maxDrawdownDurationBars,
        BigDecimal profitFactor,
        int        maxConsecutiveLosses,
        BigDecimal tradesPerMonth,
        BigDecimal tStatistic,
        BigDecimal pnlStdDev
) {}
