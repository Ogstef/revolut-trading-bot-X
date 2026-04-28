package com.stefo.revolut_trading_bot.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Pure-function math for BacktestStats. No DB, no Spring, no clock.
 * Same inputs → identical outputs. Mirrors the formulas in
 * {@code portfolio/TradeService.computeFeeNetStats()} (which is JPA-coupled
 * and not directly reusable).
 */
public final class BacktestStatsCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int SCALE = 8;
    private static final BigDecimal SQRT_252 = BigDecimal.valueOf(Math.sqrt(252));

    private BacktestStatsCalculator() {}

    public static BacktestStats compute(
            List<SimulatedTrade> trades,
            List<EquityPoint> equityCurve,
            LocalDateTime startDate,
            LocalDateTime endDate) {

        if (trades.isEmpty()) {
            return zeroStats();
        }

        int totalTrades = trades.size();
        int winningTrades = (int) trades.stream().filter(t -> t.pnl().signum() > 0).count();
        int losingTrades  = totalTrades - winningTrades;

        BigDecimal totalPnl  = sum(trades, SimulatedTrade::pnl);
        BigDecimal totalNet  = sum(trades, SimulatedTrade::netPnl);
        BigDecimal totalFees = sum(trades, t -> t.entryFee().add(t.exitFee()));
        BigDecimal totalSlip = sum(trades, t -> t.entrySlippage().add(t.exitSlippage()));

        BigDecimal avgWin  = mean(trades.stream().filter(t -> t.pnl().signum() > 0).map(SimulatedTrade::pnl).toList());
        BigDecimal avgLoss = mean(trades.stream().filter(t -> t.pnl().signum() <= 0).map(SimulatedTrade::pnl).toList());

        BigDecimal best  = trades.stream().map(SimulatedTrade::pnl).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        BigDecimal worst = trades.stream().map(SimulatedTrade::pnl).min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);

        BigDecimal winRate = BigDecimal.valueOf(winningTrades)
                .divide(BigDecimal.valueOf(totalTrades), SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);

        BigDecimal winRateFrac = winRate.divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
        BigDecimal lossRateFrac = BigDecimal.ONE.subtract(winRateFrac);
        BigDecimal expectancy   = winRateFrac.multiply(avgWin).add(lossRateFrac.multiply(avgLoss))
                                              .setScale(4, RoundingMode.HALF_UP);

        BigDecimal avgNetWin  = mean(trades.stream().filter(t -> t.netPnl().signum() > 0).map(SimulatedTrade::netPnl).toList());
        BigDecimal avgNetLoss = mean(trades.stream().filter(t -> t.netPnl().signum() <= 0).map(SimulatedTrade::netPnl).toList());
        BigDecimal netExpectancy = winRateFrac.multiply(avgNetWin).add(lossRateFrac.multiply(avgNetLoss))
                                              .setScale(4, RoundingMode.HALF_UP);

        BigDecimal feeDragPct = totalPnl.abs().signum() == 0 ? BigDecimal.ZERO
                : totalFees.add(totalSlip).divide(totalPnl.abs(), 4, RoundingMode.HALF_UP)
                                          .multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);

        // ─── Backtest-specific metrics ─────────────────────────────────────────
        BigDecimal pnlStdDev = stdDev(trades.stream().map(SimulatedTrade::netPnl).toList());
        BigDecimal tStat = tStatistic(trades.stream().map(SimulatedTrade::netPnl).toList());
        BigDecimal sharpe = sharpeRatio(equityCurve);
        DrawdownInfo dd = computeMaxDrawdown(equityCurve);
        BigDecimal profitFactor = computeProfitFactor(trades);
        int maxConsecutiveLosses = computeMaxConsecutiveLosses(trades);
        BigDecimal tradesPerMonth = computeTradesPerMonth(totalTrades, startDate, endDate);

        return new BacktestStats(
                totalTrades, winningTrades, losingTrades, winRate,
                totalPnl.setScale(2, RoundingMode.HALF_UP),
                avgWin.setScale(2, RoundingMode.HALF_UP),
                avgLoss.setScale(2, RoundingMode.HALF_UP),
                best.setScale(2, RoundingMode.HALF_UP),
                worst.setScale(2, RoundingMode.HALF_UP),
                expectancy,
                totalNet.setScale(2, RoundingMode.HALF_UP),
                totalFees.setScale(2, RoundingMode.HALF_UP),
                totalSlip.setScale(2, RoundingMode.HALF_UP),
                feeDragPct,
                netExpectancy,
                sharpe,
                dd.maxDrawdown.setScale(2, RoundingMode.HALF_UP),
                dd.maxDrawdownPct.setScale(4, RoundingMode.HALF_UP),
                dd.maxDrawdownDurationBars,
                profitFactor,
                maxConsecutiveLosses,
                tradesPerMonth,
                tStat,
                pnlStdDev.setScale(4, RoundingMode.HALF_UP)
        );
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static BigDecimal sum(List<SimulatedTrade> trades,
                                   java.util.function.Function<SimulatedTrade, BigDecimal> field) {
        return trades.stream()
                .map(field)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal stdDev(List<BigDecimal> values) {
        if (values.size() < 2) return BigDecimal.ZERO;
        BigDecimal mean = mean(values);
        BigDecimal sumSquaredDiff = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            BigDecimal diff = v.subtract(mean);
            sumSquaredDiff = sumSquaredDiff.add(diff.multiply(diff));
        }
        BigDecimal variance = sumSquaredDiff.divide(
                BigDecimal.valueOf(values.size() - 1), SCALE, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
    }

    private static BigDecimal tStatistic(List<BigDecimal> values) {
        if (values.size() < 2) return BigDecimal.ZERO;
        BigDecimal mean = mean(values);
        BigDecimal sd   = stdDev(values);
        if (sd.signum() == 0) return BigDecimal.ZERO;
        BigDecimal sqrtN = BigDecimal.valueOf(Math.sqrt(values.size()));
        BigDecimal stdErr = sd.divide(sqrtN, SCALE, RoundingMode.HALF_UP);
        return mean.divide(stdErr, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal sharpeRatio(List<EquityPoint> equityCurve) {
        if (equityCurve.size() < 3) return BigDecimal.ZERO;
        // Per-bar returns; annualize with sqrt(252) — a rough heuristic when bars aren't daily,
        // but consistent across runs of the same interval, which is what matters for ranking.
        java.util.List<BigDecimal> returns = new java.util.ArrayList<>();
        for (int i = 1; i < equityCurve.size(); i++) {
            BigDecimal prev = equityCurve.get(i - 1).equity();
            BigDecimal curr = equityCurve.get(i).equity();
            if (prev.signum() == 0) continue;
            returns.add(curr.subtract(prev).divide(prev, SCALE, RoundingMode.HALF_UP));
        }
        if (returns.size() < 2) return BigDecimal.ZERO;
        BigDecimal mean = mean(returns);
        BigDecimal sd = stdDev(returns);
        if (sd.signum() == 0) return BigDecimal.ZERO;
        return mean.divide(sd, SCALE, RoundingMode.HALF_UP)
                   .multiply(SQRT_252)
                   .setScale(4, RoundingMode.HALF_UP);
    }

    private record DrawdownInfo(BigDecimal maxDrawdown, BigDecimal maxDrawdownPct, int maxDrawdownDurationBars) {}

    private static DrawdownInfo computeMaxDrawdown(List<EquityPoint> equityCurve) {
        if (equityCurve.isEmpty()) {
            return new DrawdownInfo(BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
        BigDecimal peak = equityCurve.get(0).equity();
        BigDecimal maxDd = BigDecimal.ZERO;
        BigDecimal maxDdPct = BigDecimal.ZERO;
        int peakIdx = 0;
        int maxDurBars = 0;
        for (int i = 0; i < equityCurve.size(); i++) {
            BigDecimal e = equityCurve.get(i).equity();
            if (e.compareTo(peak) > 0) {
                peak = e;
                peakIdx = i;
            } else {
                BigDecimal dd = peak.subtract(e);
                if (dd.compareTo(maxDd) > 0) {
                    maxDd = dd;
                    maxDdPct = peak.signum() == 0 ? BigDecimal.ZERO
                            : dd.divide(peak, SCALE, RoundingMode.HALF_UP);
                    maxDurBars = i - peakIdx;
                }
            }
        }
        return new DrawdownInfo(maxDd, maxDdPct, maxDurBars);
    }

    private static BigDecimal computeProfitFactor(List<SimulatedTrade> trades) {
        BigDecimal grossWins = BigDecimal.ZERO;
        BigDecimal grossLosses = BigDecimal.ZERO;
        for (SimulatedTrade t : trades) {
            if (t.netPnl().signum() > 0) grossWins = grossWins.add(t.netPnl());
            else grossLosses = grossLosses.add(t.netPnl().abs());
        }
        if (grossLosses.signum() == 0) {
            return grossWins.signum() > 0 ? BigDecimal.valueOf(999) : BigDecimal.ZERO;
        }
        return grossWins.divide(grossLosses, 4, RoundingMode.HALF_UP);
    }

    private static int computeMaxConsecutiveLosses(List<SimulatedTrade> trades) {
        int max = 0;
        int current = 0;
        for (SimulatedTrade t : trades) {
            if (t.netPnl().signum() <= 0) {
                current++;
                if (current > max) max = current;
            } else {
                current = 0;
            }
        }
        return max;
    }

    private static BigDecimal computeTradesPerMonth(int totalTrades, LocalDateTime start, LocalDateTime end) {
        long days = Duration.between(start, end).toDays();
        if (days <= 0) return BigDecimal.ZERO;
        BigDecimal months = BigDecimal.valueOf(days).divide(BigDecimal.valueOf(30.4375), SCALE, RoundingMode.HALF_UP);
        if (months.signum() == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(totalTrades).divide(months, 2, RoundingMode.HALF_UP);
    }

    private static BacktestStats zeroStats() {
        return new BacktestStats(
                0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
