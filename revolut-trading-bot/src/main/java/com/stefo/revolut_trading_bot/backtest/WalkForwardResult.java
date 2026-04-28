package com.stefo.revolut_trading_bot.backtest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Output of POST /api/backtest/walk-forward — same config replayed on N
 * consecutive sub-windows of [startDate, endDate]. The varianceMetrics block
 * answers "is this edge stable across regimes or did it just get lucky in one
 * window?" by measuring how much the per-window stats moved around.
 *
 * skippedWindows surfaces sub-windows that the simulator could not run (e.g.
 * because no candles exist in that range — common when 15m history only goes
 * back ~47 days and the user picks a longer total range).
 */
public record WalkForwardResult(
        List<BacktestRunSummary> windows,
        List<SkippedWindow> skippedWindows,
        VarianceMetrics varianceMetrics
) {

    /**
     * One sub-window that the simulator rejected. {@code reason} is the
     * exception message verbatim — usually "Insufficient candles for backtest
     * window: N bars available …".
     */
    public record SkippedWindow(
            int index,                    // 1-based, matches the i+1 in the loop
            LocalDateTime startDate,
            LocalDateTime endDate,
            String reason
    ) {}

    /**
     * verdict thresholds (heuristic, computed against winRate stddev / mean):
     *   STABLE                        — stddev / |mean| < 0.30
     *   REGIME_DEPENDENT              — 0.30 ≤ stddev / |mean| < 0.70
     *   WILDLY_VARYING_HIGH_VARIANCE  — stddev / |mean| ≥ 0.70 with positive mean
     *   WILDLY_VARYING_NEGATIVE       — mean ≤ 0 (no edge across windows)
     */
    public record VarianceMetrics(
            BigDecimal winRateStdDev,
            BigDecimal expectancyStdDev,
            BigDecimal netPnlStdDev,
            String consistencyVerdict
    ) {}
}
