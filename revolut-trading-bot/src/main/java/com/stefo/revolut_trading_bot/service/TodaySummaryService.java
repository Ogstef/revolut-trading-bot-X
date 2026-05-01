package com.stefo.revolut_trading_bot.service;

import com.stefo.revolut_trading_bot.market.MarketDataService;
import com.stefo.revolut_trading_bot.model.dto.PositionView;
import com.stefo.revolut_trading_bot.model.dto.TodaySummary;
import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
import com.stefo.revolut_trading_bot.repository.PositionRepository;
import com.stefo.revolut_trading_bot.repository.TradeRepository;
import com.stefo.revolut_trading_bot.utils.PositionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the "Today" tab summary — aggregates closed trades since start of day,
 * groups them by (pair, interval, strategy), and lists positions opened today.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodaySummaryService {

    private final TradeRepository tradeRepository;
    private final PositionRepository positionRepository;
    private final MarketDataService marketDataService;

    public TodaySummary build() {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();

        List<Trade> closedToday = tradeRepository.findClosedTradesSince(startOfDay).stream()
                .filter(t -> t.getPnl() != null)
                .sorted(Comparator.comparing(Trade::getClosedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        int total = closedToday.size();
        int wins = (int) closedToday.stream()
                .filter(t -> t.getPnl().compareTo(BigDecimal.ZERO) > 0).count();
        int losses = total - wins;

        BigDecimal grossPnl = sum(closedToday, Trade::getPnl);
        BigDecimal netPnl = closedToday.stream()
                .map(t -> t.getNetPnl() != null ? t.getNetPnl() : t.getPnl())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fees = closedToday.stream()
                .map(t -> t.getEntryFee().add(t.getExitFee()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal slippage = closedToday.stream()
                .map(t -> t.getEntrySlippage().add(t.getExitSlippage()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCosts = fees.add(slippage);

        BigDecimal best = closedToday.stream().map(Trade::getPnl)
                .max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        BigDecimal worst = closedToday.stream().map(Trade::getPnl)
                .min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);

        BigDecimal averageWin = wins == 0 ? BigDecimal.ZERO
                : closedToday.stream()
                .filter(t -> t.getPnl().compareTo(BigDecimal.ZERO) > 0)
                .map(Trade::getPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(wins), 8, RoundingMode.HALF_UP);
        BigDecimal averageLoss = losses == 0 ? BigDecimal.ZERO
                : closedToday.stream()
                .filter(t -> t.getPnl().compareTo(BigDecimal.ZERO) <= 0)
                .map(Trade::getPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(losses), 8, RoundingMode.HALF_UP);

        BigDecimal winRate = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(wins)
                .divide(BigDecimal.valueOf(total), 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal winRateFrac = winRate.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        BigDecimal lossRateFrac = BigDecimal.ONE.subtract(winRateFrac);
        BigDecimal expectancy = total == 0 ? BigDecimal.ZERO
                : winRateFrac.multiply(averageWin).add(lossRateFrac.multiply(averageLoss))
                .setScale(4, RoundingMode.HALF_UP);

        BigDecimal feeDragPct = grossPnl.abs().compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : totalCosts.divide(grossPnl.abs(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        // ─── Per-triple breakdown ────────────────────────────────────────────
        Map<String, TripleAccum> byKey = new HashMap<>();
        for (Trade t : closedToday) {
            String k = t.getPair() + "|" + t.getInterval() + "|" + t.getStrategyName().name();
            TripleAccum acc = byKey.computeIfAbsent(k, kk -> new TripleAccum(
                    t.getPair(), t.getInterval(),
                    t.getStrategyName().name(), t.getStrategyName().getDisplayName()));
            acc.add(t);
        }
        List<TodaySummary.TodayTripleRow> byTriple = byKey.values().stream()
                .map(TripleAccum::toRow)
                .sorted(Comparator.comparing(TodaySummary.TodayTripleRow::netPnl).reversed())
                .toList();

        // ─── Open positions opened today ─────────────────────────────────────
        List<Position> openNow = positionRepository.findByStatus(OrderStatus.OPEN);
        List<Position> openedToday = openNow.stream()
                .filter(p -> p.getOpenedAt() != null
                        && !p.getOpenedAt().toLocalDate().isBefore(today))
                .toList();

        List<PositionView> openPositionViews = new ArrayList<>(openedToday.size());
        Map<String, BigDecimal> priceByPair = new HashMap<>();
        for (Position p : openedToday) {
            BigDecimal price = priceByPair.computeIfAbsent(
                    p.getPair(), marketDataService::getCurrentPriceForPair);
            openPositionViews.add(PositionUtils.toPositionView(p, price));
        }

        log.debug("Today summary built — {} closed trades, {} open positions opened today",
                total, openPositionViews.size());

        return new TodaySummary(
                today,
                startOfDay,
                LocalDateTime.now(),
                total, wins, losses, winRate,
                grossPnl.setScale(2, RoundingMode.HALF_UP),
                netPnl.setScale(2, RoundingMode.HALF_UP),
                fees.setScale(2, RoundingMode.HALF_UP),
                slippage.setScale(2, RoundingMode.HALF_UP),
                feeDragPct,
                best.setScale(2, RoundingMode.HALF_UP),
                worst.setScale(2, RoundingMode.HALF_UP),
                averageWin.setScale(2, RoundingMode.HALF_UP),
                averageLoss.setScale(2, RoundingMode.HALF_UP),
                expectancy,
                openedToday.size(),
                total,
                openNow.size(),
                byTriple,
                closedToday,
                openPositionViews
        );
    }

    private static BigDecimal sum(List<Trade> trades, java.util.function.Function<Trade, BigDecimal> f) {
        return trades.stream().map(f)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static class TripleAccum {
        final String pair;
        final String interval;
        final String strategy;
        final String displayName;
        int total = 0;
        int wins = 0;
        int losses = 0;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal costs = BigDecimal.ZERO;

        TripleAccum(String pair, String interval, String strategy, String displayName) {
            this.pair = pair;
            this.interval = interval;
            this.strategy = strategy;
            this.displayName = displayName;
        }

        void add(Trade t) {
            total++;
            if (t.getPnl().compareTo(BigDecimal.ZERO) > 0) wins++;
            else losses++;
            gross = gross.add(t.getPnl());
            net = net.add(t.getNetPnl() != null ? t.getNetPnl() : t.getPnl());
            costs = costs
                    .add(t.getEntryFee()).add(t.getExitFee())
                    .add(t.getEntrySlippage()).add(t.getExitSlippage());
        }

        TodaySummary.TodayTripleRow toRow() {
            return new TodaySummary.TodayTripleRow(
                    pair, interval, strategy, displayName,
                    total, wins, losses,
                    gross.setScale(2, RoundingMode.HALF_UP),
                    net.setScale(2, RoundingMode.HALF_UP),
                    costs.setScale(2, RoundingMode.HALF_UP)
            );
        }
    }
}
