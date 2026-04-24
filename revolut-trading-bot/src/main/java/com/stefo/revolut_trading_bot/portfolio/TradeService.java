package com.stefo.revolut_trading_bot.portfolio;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.dto.PnlBreakdown;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.model.enums.TradingVehicle;
import com.stefo.revolut_trading_bot.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Computes statistics across all closed trades.
 *
 * Only trades with a non-null PnL are counted — open (incomplete) trade records
 * are excluded since they don't have a final result yet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TradeService {

    private final TradeRepository tradeRepository;
    private final TradingConfig config;

    /**
     * Computes aggregate statistics across all completed trades for the configured pair.
     */
    public TradingStats getStats() {
        List<Trade> closed = tradeRepository.findByPairOrderByExecutedAtDesc(config.primaryPair())
                .stream()
                .filter(t -> t.getPnl() != null)   // exclude open trade records
                .toList();

        if (closed.isEmpty()) {
            return emptyStats();
        }

        List<Trade> winners = closed.stream()
                .filter(t -> t.getPnl().compareTo(BigDecimal.ZERO) > 0)
                .toList();
        List<Trade> losers = closed.stream()
                .filter(t -> t.getPnl().compareTo(BigDecimal.ZERO) <= 0)
                .toList();

        BigDecimal totalPnl   = sum(closed);
        BigDecimal avgWin     = winners.isEmpty() ? BigDecimal.ZERO : avg(winners);
        BigDecimal avgLoss    = losers.isEmpty()  ? BigDecimal.ZERO : avg(losers);
        BigDecimal winRate    = BigDecimal.valueOf(winners.size())
                .divide(BigDecimal.valueOf(closed.size()), 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal best  = closed.stream().map(Trade::getPnl).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        BigDecimal worst = closed.stream().map(Trade::getPnl).min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);

        BigDecimal winRateFrac  = winRate.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        BigDecimal lossRateFrac = BigDecimal.ONE.subtract(winRateFrac);
        BigDecimal expectancy   = winRateFrac.multiply(avgWin)
                .add(lossRateFrac.multiply(avgLoss))
                .setScale(4, RoundingMode.HALF_UP);

        FeeNetStats feeNet = computeFeeNetStats(closed, winRateFrac, lossRateFrac);

        log.debug("Stats — total={} wins={} losses={} winRate={}% pnl={} expectancy={}",
                closed.size(), winners.size(), losers.size(), winRate, totalPnl, expectancy);

        return new TradingStats(
                closed.size(), winners.size(), losers.size(),
                winRate, totalPnl.setScale(2, RoundingMode.HALF_UP),
                avgWin.setScale(2, RoundingMode.HALF_UP),
                avgLoss.setScale(2, RoundingMode.HALF_UP),
                best.setScale(2, RoundingMode.HALF_UP),
                worst.setScale(2, RoundingMode.HALF_UP),
                expectancy,
                feeNet.netPnl(), feeNet.totalFees(), feeNet.totalSlippage(),
                feeNet.feeDragPct(), feeNet.netExpectancy()
        );
    }

    /**
     * Returns PnL totals broken down by day / week / month / all-time (global).
     */
    public PnlBreakdown getPnlBreakdown() {
        LocalDateTime startOfDay   = LocalDate.now().atStartOfDay();
        LocalDateTime startOfWeek  = LocalDate.now().with(java.time.DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime startOfMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        LocalDateTime epoch = LocalDateTime.of(2000, 1, 1, 0, 0);

        BigDecimal daily   = tradeRepository.sumPnlSince(startOfDay);
        BigDecimal weekly  = tradeRepository.sumPnlSince(startOfWeek);
        BigDecimal monthly = tradeRepository.sumPnlSince(startOfMonth);
        BigDecimal allTime = tradeRepository.sumPnlSince(epoch);

        BigDecimal dailyNet   = tradeRepository.sumNetPnlSince(startOfDay);
        BigDecimal weeklyNet  = tradeRepository.sumNetPnlSince(startOfWeek);
        BigDecimal monthlyNet = tradeRepository.sumNetPnlSince(startOfMonth);
        BigDecimal allTimeNet = tradeRepository.sumNetPnlSince(epoch);

        return new PnlBreakdown(
                daily.setScale(2, RoundingMode.HALF_UP),
                weekly.setScale(2, RoundingMode.HALF_UP),
                monthly.setScale(2, RoundingMode.HALF_UP),
                allTime.setScale(2, RoundingMode.HALF_UP),
                dailyNet.setScale(2, RoundingMode.HALF_UP),
                weeklyNet.setScale(2, RoundingMode.HALF_UP),
                monthlyNet.setScale(2, RoundingMode.HALF_UP),
                allTimeNet.setScale(2, RoundingMode.HALF_UP)
        );
    }

    /**
     * Strategy-scoped statistics — same logic as getStats() but filtered to one strategy.
     * Backward-compatible: uses primary pair and primary interval.
     */
    public TradingStats getStatsForStrategy(StrategyType strategyName) {
        return getStatsForStrategy(null, null, strategyName);
    }

    /**
     * Strategy-scoped statistics filtered to a specific (pair, interval, strategy).
     */
    public TradingStats getStatsForStrategy(String pair, String interval, StrategyType strategyName) {
        return getStatsForStrategy(pair, interval, strategyName, null);
    }

    /** Vehicle-scoped variant — when {@code vehicle} is null, aggregates across all vehicles. */
    public TradingStats getStatsForStrategy(String pair, String interval, StrategyType strategyName, TradingVehicle vehicle) {
        String effectivePair     = pair != null ? pair : config.primaryPair();
        String effectiveInterval = interval != null ? interval : config.primaryInterval();

        List<Trade> closed = (vehicle == null
                ? tradeRepository.findByPairAndIntervalAndStrategyNameOrderByExecutedAtDesc(effectivePair, effectiveInterval, strategyName)
                : tradeRepository.findByPairAndIntervalAndStrategyNameAndVehicleOrderByExecutedAtDesc(effectivePair, effectiveInterval, strategyName, vehicle))
                .stream()
                .filter(t -> t.getPnl() != null)
                .toList();

        if (closed.isEmpty()) {
            return emptyStats();
        }

        List<Trade> winners = closed.stream().filter(t -> t.getPnl().compareTo(BigDecimal.ZERO) > 0).toList();
        List<Trade> losers  = closed.stream().filter(t -> t.getPnl().compareTo(BigDecimal.ZERO) <= 0).toList();

        BigDecimal totalPnl   = sum(closed);
        BigDecimal avgWin     = winners.isEmpty() ? BigDecimal.ZERO : avg(winners);
        BigDecimal avgLoss    = losers.isEmpty()  ? BigDecimal.ZERO : avg(losers);
        BigDecimal winRate    = BigDecimal.valueOf(winners.size())
                .divide(BigDecimal.valueOf(closed.size()), 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal best  = closed.stream().map(Trade::getPnl).max(java.util.Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        BigDecimal worst = closed.stream().map(Trade::getPnl).min(java.util.Comparator.naturalOrder()).orElse(BigDecimal.ZERO);

        BigDecimal winRateFrac  = winRate.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        BigDecimal lossRateFrac = BigDecimal.ONE.subtract(winRateFrac);
        BigDecimal expectancy   = winRateFrac.multiply(avgWin)
                .add(lossRateFrac.multiply(avgLoss))
                .setScale(4, RoundingMode.HALF_UP);

        FeeNetStats feeNet = computeFeeNetStats(closed, winRateFrac, lossRateFrac);

        return new TradingStats(
                closed.size(), winners.size(), losers.size(),
                winRate, totalPnl.setScale(2, RoundingMode.HALF_UP),
                avgWin.setScale(2, RoundingMode.HALF_UP),
                avgLoss.setScale(2, RoundingMode.HALF_UP),
                best.setScale(2, RoundingMode.HALF_UP),
                worst.setScale(2, RoundingMode.HALF_UP),
                expectancy,
                feeNet.netPnl(), feeNet.totalFees(), feeNet.totalSlippage(),
                feeNet.feeDragPct(), feeNet.netExpectancy()
        );
    }

    /**
     * Strategy-scoped PnL breakdown — backward-compatible.
     */
    public PnlBreakdown getPnlBreakdownForStrategy(StrategyType strategyName) {
        return getPnlBreakdownForStrategy(null, null, strategyName);
    }

    /**
     * Strategy-scoped PnL breakdown filtered to a specific (pair, interval, strategy).
     */
    public PnlBreakdown getPnlBreakdownForStrategy(String pair, String interval, StrategyType strategyName) {
        return getPnlBreakdownForStrategy(pair, interval, strategyName, null);
    }

    /** Vehicle-scoped variant — when {@code vehicle} is null, sums across all vehicles. */
    public PnlBreakdown getPnlBreakdownForStrategy(String pair, String interval, StrategyType strategyName, TradingVehicle vehicle) {
        String effectivePair     = pair != null ? pair : config.primaryPair();
        String effectiveInterval = interval != null ? interval : config.primaryInterval();

        LocalDateTime startOfDay   = LocalDate.now().atStartOfDay();
        LocalDateTime startOfWeek  = LocalDate.now().with(java.time.DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime startOfMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        LocalDateTime epoch        = LocalDateTime.of(2000, 1, 1, 0, 0);

        BigDecimal daily, weekly, monthly, allTime, dailyNet, weeklyNet, monthlyNet, allTimeNet;
        if (vehicle == null) {
            daily   = tradeRepository.sumPnlClosedSinceAndPairAndIntervalAndStrategy(startOfDay,   effectivePair, effectiveInterval, strategyName);
            weekly  = tradeRepository.sumPnlClosedSinceAndPairAndIntervalAndStrategy(startOfWeek,  effectivePair, effectiveInterval, strategyName);
            monthly = tradeRepository.sumPnlClosedSinceAndPairAndIntervalAndStrategy(startOfMonth, effectivePair, effectiveInterval, strategyName);
            allTime = tradeRepository.sumPnlClosedSinceAndPairAndIntervalAndStrategy(epoch,        effectivePair, effectiveInterval, strategyName);
            dailyNet   = tradeRepository.sumNetPnlClosedSinceAndPairAndIntervalAndStrategy(startOfDay,   effectivePair, effectiveInterval, strategyName);
            weeklyNet  = tradeRepository.sumNetPnlClosedSinceAndPairAndIntervalAndStrategy(startOfWeek,  effectivePair, effectiveInterval, strategyName);
            monthlyNet = tradeRepository.sumNetPnlClosedSinceAndPairAndIntervalAndStrategy(startOfMonth, effectivePair, effectiveInterval, strategyName);
            allTimeNet = tradeRepository.sumNetPnlClosedSinceAndPairAndIntervalAndStrategy(epoch,        effectivePair, effectiveInterval, strategyName);
        } else {
            daily   = tradeRepository.sumPnlClosedSinceAndPairAndIntervalAndStrategyAndVehicle(startOfDay,   effectivePair, effectiveInterval, strategyName, vehicle);
            weekly  = tradeRepository.sumPnlClosedSinceAndPairAndIntervalAndStrategyAndVehicle(startOfWeek,  effectivePair, effectiveInterval, strategyName, vehicle);
            monthly = tradeRepository.sumPnlClosedSinceAndPairAndIntervalAndStrategyAndVehicle(startOfMonth, effectivePair, effectiveInterval, strategyName, vehicle);
            allTime = tradeRepository.sumPnlClosedSinceAndPairAndIntervalAndStrategyAndVehicle(epoch,        effectivePair, effectiveInterval, strategyName, vehicle);
            dailyNet   = tradeRepository.sumNetPnlClosedSinceAndPairAndIntervalAndStrategyAndVehicle(startOfDay,   effectivePair, effectiveInterval, strategyName, vehicle);
            weeklyNet  = tradeRepository.sumNetPnlClosedSinceAndPairAndIntervalAndStrategyAndVehicle(startOfWeek,  effectivePair, effectiveInterval, strategyName, vehicle);
            monthlyNet = tradeRepository.sumNetPnlClosedSinceAndPairAndIntervalAndStrategyAndVehicle(startOfMonth, effectivePair, effectiveInterval, strategyName, vehicle);
            allTimeNet = tradeRepository.sumNetPnlClosedSinceAndPairAndIntervalAndStrategyAndVehicle(epoch,        effectivePair, effectiveInterval, strategyName, vehicle);
        }

        return new PnlBreakdown(
                daily.setScale(2, RoundingMode.HALF_UP),
                weekly.setScale(2, RoundingMode.HALF_UP),
                monthly.setScale(2, RoundingMode.HALF_UP),
                allTime.setScale(2, RoundingMode.HALF_UP),
                dailyNet.setScale(2, RoundingMode.HALF_UP),
                weeklyNet.setScale(2, RoundingMode.HALF_UP),
                monthlyNet.setScale(2, RoundingMode.HALF_UP),
                allTimeNet.setScale(2, RoundingMode.HALF_UP)
        );
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private BigDecimal sum(List<Trade> trades) {
        return trades.stream()
                .map(Trade::getPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal avg(List<Trade> trades) {
        return sum(trades).divide(BigDecimal.valueOf(trades.size()), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal effectiveNet(Trade t) {
        return t.getNetPnl() != null ? t.getNetPnl() : t.getPnl();
    }

    private FeeNetStats computeFeeNetStats(List<Trade> closed, BigDecimal winRateFrac, BigDecimal lossRateFrac) {
        BigDecimal totalNetPnl   = closed.stream().map(this::effectiveNet).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFees     = closed.stream().map(t -> t.getEntryFee().add(t.getExitFee())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSlippage = closed.stream().map(t -> t.getEntrySlippage().add(t.getExitSlippage())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCosts    = totalFees.add(totalSlippage);

        List<Trade> netWinners = closed.stream().filter(t -> effectiveNet(t).compareTo(BigDecimal.ZERO) > 0).toList();
        List<Trade> netLosers  = closed.stream().filter(t -> effectiveNet(t).compareTo(BigDecimal.ZERO) <= 0).toList();
        BigDecimal avgNetWin  = netWinners.isEmpty() ? BigDecimal.ZERO
                : netWinners.stream().map(this::effectiveNet).reduce(BigDecimal.ZERO, BigDecimal::add)
                             .divide(BigDecimal.valueOf(netWinners.size()), 8, RoundingMode.HALF_UP);
        BigDecimal avgNetLoss = netLosers.isEmpty()  ? BigDecimal.ZERO
                : netLosers.stream().map(this::effectiveNet).reduce(BigDecimal.ZERO, BigDecimal::add)
                             .divide(BigDecimal.valueOf(netLosers.size()), 8, RoundingMode.HALF_UP);

        BigDecimal grossPnl  = closed.stream().map(Trade::getPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal feeDragPct = grossPnl.abs().compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : totalCosts.divide(grossPnl.abs(), 4, RoundingMode.HALF_UP)
                             .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);

        BigDecimal netExpectancy = winRateFrac.multiply(avgNetWin)
                .add(lossRateFrac.multiply(avgNetLoss))
                .setScale(4, RoundingMode.HALF_UP);

        return new FeeNetStats(
                totalNetPnl.setScale(2, RoundingMode.HALF_UP),
                totalFees.setScale(2, RoundingMode.HALF_UP),
                totalSlippage.setScale(2, RoundingMode.HALF_UP),
                feeDragPct,
                netExpectancy
        );
    }

    private TradingStats emptyStats() {
        return new TradingStats(0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private record FeeNetStats(
            BigDecimal netPnl,
            BigDecimal totalFees,
            BigDecimal totalSlippage,
            BigDecimal feeDragPct,
            BigDecimal netExpectancy
    ) {}
}
