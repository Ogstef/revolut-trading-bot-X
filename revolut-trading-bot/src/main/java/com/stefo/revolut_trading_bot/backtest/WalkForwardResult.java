package com.stefo.revolut_trading_bot.backtest;

import java.math.BigDecimal;
import java.util.List;

/**
 * Output of POST /api/backtest/walk-forward — same config replayed on N
 * consecutive sub-windows of [startDate, endDate]. The varianceMetrics block
 * answers "is this edge stable across regimes or did it just get lucky in one
 * window?" by measuring how much the per-window stats moved around.
 */
public record WalkForwardResult(
        List<BacktestRunSummary> windows,
        VarianceMetrics varianceMetrics
) {

    /**
     * verdict thresholds (heuristic, computed against winRate stddev / mean):
     *   STABLE             — stddev / |mean| < 0.30
     *   REGIME_DEPENDENT   — 0.30 ≤ stddev / |mean| < 0.70
     *   WILDLY_VARYING     — stddev / |mean| ≥ 0.70  (or mean ≤ 0 → likely noise)
     */
    public record VarianceMetrics(
            BigDecimal winRateStdDev,
            BigDecimal expectancyStdDev,
            BigDecimal netPnlStdDev,
            String consistencyVerdict
    ) {}
}
