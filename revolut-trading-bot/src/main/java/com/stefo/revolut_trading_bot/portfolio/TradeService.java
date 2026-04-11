package com.stefo.revolut_trading_bot.portfolio;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.dto.PnlBreakdown;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
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

        // Expectancy: average profit per trade (positive = profitable strategy)
        BigDecimal winRateFrac  = winRate.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        BigDecimal lossRateFrac = BigDecimal.ONE.subtract(winRateFrac);
        BigDecimal expectancy   = winRateFrac.multiply(avgWin)
                .add(lossRateFrac.multiply(avgLoss))
                .setScale(4, RoundingMode.HALF_UP);

        log.debug("Stats — total={} wins={} losses={} winRate={}% pnl={} expectancy={}",
                closed.size(), winners.size(), losers.size(), winRate, totalPnl, expectancy);

        return new TradingStats(
                closed.size(), winners.size(), losers.size(),
                winRate, totalPnl.setScale(2, RoundingMode.HALF_UP),
                avgWin.setScale(2, RoundingMode.HALF_UP),
                avgLoss.setScale(2, RoundingMode.HALF_UP),
                best.setScale(2, RoundingMode.HALF_UP),
                worst.setScale(2, RoundingMode.HALF_UP),
                expectancy
        );
    }

    /**
     * Returns PnL totals broken down by day / week / month / all-time (global).
     */
    public PnlBreakdown getPnlBreakdown() {
        LocalDateTime startOfDay   = LocalDate.now().atStartOfDay();
        LocalDateTime startOfWeek  = LocalDate.now().with(java.time.DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime startOfMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        BigDecimal daily   = tradeRepository.sumPnlSince(startOfDay);
        BigDecimal weekly  = tradeRepository.sumPnlSince(startOfWeek);
        BigDecimal monthly = tradeRepository.sumPnlSince(startOfMonth);
        BigDecimal allTime = tradeRepository.sumPnlSince(LocalDateTime.of(2000, 1, 1, 0, 0));

        return new PnlBreakdown(
                daily.setScale(2, RoundingMode.HALF_UP),
                weekly.setScale(2, RoundingMode.HALF_UP),
                monthly.setScale(2, RoundingMode.HALF_UP),
                allTime.setScale(2, RoundingMode.HALF_UP)
        );
    }

    /**
     * Strategy-scoped statistics — same logic as getStats() but filtered to one strategy.
     */
    public TradingStats getStatsForStrategy(StrategyType strategyName) {
        List<Trade> closed = tradeRepository.findByStrategyNameOrderByExecutedAtDesc(strategyName)
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

        return new TradingStats(
                closed.size(), winners.size(), losers.size(),
                winRate, totalPnl.setScale(2, RoundingMode.HALF_UP),
                avgWin.setScale(2, RoundingMode.HALF_UP),
                avgLoss.setScale(2, RoundingMode.HALF_UP),
                best.setScale(2, RoundingMode.HALF_UP),
                worst.setScale(2, RoundingMode.HALF_UP),
                expectancy
        );
    }

    /**
     * Strategy-scoped PnL breakdown.
     */
    public PnlBreakdown getPnlBreakdownForStrategy(StrategyType strategyName) {
        LocalDateTime startOfDay   = LocalDate.now().atStartOfDay();
        LocalDateTime startOfWeek  = LocalDate.now().with(java.time.DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime startOfMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        LocalDateTime epoch        = LocalDateTime.of(2000, 1, 1, 0, 0);

        BigDecimal daily   = tradeRepository.sumPnlSinceAndStrategyName(startOfDay,   strategyName);
        BigDecimal weekly  = tradeRepository.sumPnlSinceAndStrategyName(startOfWeek,  strategyName);
        BigDecimal monthly = tradeRepository.sumPnlSinceAndStrategyName(startOfMonth, strategyName);
        BigDecimal allTime = tradeRepository.sumPnlSinceAndStrategyName(epoch,        strategyName);

        return new PnlBreakdown(
                daily.setScale(2, RoundingMode.HALF_UP),
                weekly.setScale(2, RoundingMode.HALF_UP),
                monthly.setScale(2, RoundingMode.HALF_UP),
                allTime.setScale(2, RoundingMode.HALF_UP)
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

    private TradingStats emptyStats() {
        return new TradingStats(0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
