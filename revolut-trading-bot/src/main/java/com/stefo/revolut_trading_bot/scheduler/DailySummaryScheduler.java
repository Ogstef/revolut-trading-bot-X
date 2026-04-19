package com.stefo.revolut_trading_bot.scheduler;

import com.stefo.revolut_trading_bot.alert.DailySummaryData;
import com.stefo.revolut_trading_bot.alert.TelegramClient;
import com.stefo.revolut_trading_bot.alert.TelegramMessageFormatter;
import com.stefo.revolut_trading_bot.config.TelegramConfig;
import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.dto.PnlBreakdown;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.portfolio.TradeService;
import com.stefo.revolut_trading_bot.repository.PositionRepository;
import com.stefo.revolut_trading_bot.repository.TradeRepository;
import com.stefo.revolut_trading_bot.risk.RiskManager;
import com.stefo.revolut_trading_bot.strategy.SignalEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Sends a periodic P&L summary to Telegram at the configured cron interval.
 * Default: every 30 minutes — configurable via telegram.daily-summary-cron.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailySummaryScheduler {

    private final TelegramConfig telegramConfig;
    private final TelegramClient telegramClient;
    private final TelegramMessageFormatter formatter;
    private final TradeRepository tradeRepository;
    private final PositionRepository positionRepository;
    private final TradeService tradeService;
    private final TradingConfig tradingConfig;
    private final RiskManager riskManager;
    private final SignalEngine signalEngine;

    @Scheduled(cron = "${telegram.daily-summary-cron:0 0 21 * * *}")
    public void sendDailySummary() {
        if (!telegramConfig.isEnabled() || !telegramConfig.isSendDailySummary()) {
            return;
        }
        try {
            DailySummaryData data = buildSummaryData();
            String message = formatter.formatDailySummary(data);
            telegramClient.sendMessage(message);
            log.info("Daily summary sent to Telegram — {} trades, daily PnL €{}",
                    data.totalTradesClosed(), data.dailyPnl());
        } catch (Exception e) {
            log.warn("Failed to build/send daily summary: {}", e.getMessage());
        }
    }

    private DailySummaryData buildSummaryData() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();

        // Trades closed today
        List<Trade> closedToday = tradeRepository.findClosedTradesSince(startOfDay);
        int wins = (int) closedToday.stream()
                .filter(t -> t.getPnl().signum() > 0)
                .count();
        int losses = closedToday.size() - wins;

        BigDecimal winRate = closedToday.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(wins)
                .divide(BigDecimal.valueOf(closedToday.size()), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        // PnL breakdown (gross + net via existing service)
        PnlBreakdown pnl = tradeService.getPnlBreakdown();

        // Fee aggregates for trades closed today
        BigDecimal dailyFees = closedToday.stream()
                .map(t -> safe(t.getEntryFee()).add(safe(t.getExitFee())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal dailySlippage = closedToday.stream()
                .map(t -> safe(t.getEntrySlippage()).add(safe(t.getExitSlippage())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        // Open positions
        int openPositions = (int) positionRepository.countByStatus(OrderStatus.OPEN);

        // Circuit breakers — use snapshotAll (3 DB queries) then check each triple in-memory
        int cbActive = countActiveCircuitBreakers();

        // Top winners by gross PnL (already sorted DESC by the query)
        List<DailySummaryData.TopMover> topWinners = closedToday.stream()
                .filter(t -> t.getPnl().signum() > 0)
                .limit(3)
                .map(this::toMover)
                .toList();

        // Top losers by gross PnL (worst first)
        List<DailySummaryData.TopMover> topLosers = closedToday.stream()
                .filter(t -> t.getPnl().signum() <= 0)
                .sorted(Comparator.comparing(Trade::getPnl))
                .limit(3)
                .map(this::toMover)
                .toList();

        return new DailySummaryData(
                LocalDate.now(),
                closedToday.size(),
                wins,
                losses,
                winRate,
                pnl.daily(),
                pnl.weekly(),
                pnl.allTime(),
                pnl.dailyNet(),
                pnl.weeklyNet(),
                pnl.allTimeNet(),
                dailyFees,
                dailySlippage,
                openPositions,
                cbActive,
                topWinners,
                topLosers,
                tradingConfig.getMode()
        );
    }

    private static BigDecimal safe(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private int countActiveCircuitBreakers() {
        try {
            RiskManager.CycleSnapshot snapshot = riskManager.snapshotAll();
            Map<String, Map<StrategyType, BigDecimal>> allBalances = tradingConfig.getStrategyBalances();
            BigDecimal fallback = tradingConfig.getPaperBalance();
            int count = 0;

            for (String pair : tradingConfig.getPairs()) {
                for (String interval : tradingConfig.intervalLabels()) {
                    for (StrategyType strategy : signalEngine.registeredStrategies()) {
                        BigDecimal balance = (allBalances != null
                                && allBalances.containsKey(pair)
                                && allBalances.get(pair).containsKey(strategy))
                                ? allBalances.get(pair).get(strategy) : fallback;
                        if (riskManager.statusFromSnapshot(snapshot, balance, pair, interval, strategy)
                                .anyCircuitBreakerTripped()) {
                            count++;
                        }
                    }
                }
            }
            return count;
        } catch (Exception e) {
            log.warn("Failed to count circuit breakers for daily summary: {}", e.getMessage());
            return 0;
        }
    }

    private DailySummaryData.TopMover toMover(Trade t) {
        return new DailySummaryData.TopMover(
                t.getPair(),
                t.getStrategyName().name(),
                t.getInterval(),
                t.getPnl().setScale(2, RoundingMode.HALF_UP),
                safe(t.getNetPnl()).setScale(2, RoundingMode.HALF_UP)
        );
    }
}
