package com.stefo.revolut_trading_bot.risk;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.repository.PositionRepository;
import com.stefo.revolut_trading_bot.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Guards all trade attempts against the configured risk rules.
 *
 * Rules (all checked in order — first violation rejects the trade):
 *   1. Max concurrent open positions (default 3)
 *   2. Daily loss circuit breaker — stops if today's PnL < -maxDailyLossPct% of balance
 *   3. Consecutive loss circuit breaker — stops after N back-to-back losing trades
 *
 * From Phase 8, circuit breakers are scoped per (pair, strategy) so a bad run on
 * BTC-EUR does not block the same strategy on ETH-EUR.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RiskManager {

    private final PositionRepository positionRepository;
    private final TradeRepository    tradeRepository;
    private final TradingConfig      config;

    /**
     * Validates whether a new trade is allowed.
     * Scoped to the given (pair, interval, strategy) — circuit breakers are fully isolated.
     *
     * @param availableBalance paper balance for this (pair, strategy) combination
     * @param pair             trading pair (e.g. "BTC-EUR")
     * @param interval         candle interval label (e.g. "15m", "1h")
     * @param strategyType     strategy to scope the checks to
     */
    public RiskValidationResult validateForStrategy(BigDecimal availableBalance,
                                                    String pair,
                                                    String interval,
                                                    StrategyType strategyType) {
        TradingConfig.Risk risk = config.getRisk();

        // 1. Concurrent positions cap — scoped to (pair, interval, strategy)
        long openPositions = positionRepository
                .countByStatusAndPairAndIntervalAndStrategyName(OrderStatus.OPEN, pair, interval, strategyType);

        if (openPositions >= risk.getMaxConcurrentPositions()) {
            String reason = String.format("Max concurrent positions reached (%d/%d) [pair=%s interval=%s strategy=%s]",
                    openPositions, risk.getMaxConcurrentPositions(), pair, interval, strategyType);
            log.warn("Risk rejected: {}", reason);
            return RiskValidationResult.rejected(reason);
        }

        // 2. Daily loss circuit breaker
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        BigDecimal dailyPnl = tradeRepository
                .sumPnlSinceAndPairAndIntervalAndStrategy(startOfDay, pair, interval, strategyType);

        BigDecimal maxAllowedLoss = availableBalance
                .multiply(risk.getMaxDailyLossPct())
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)
                .negate();

        if (dailyPnl.compareTo(maxAllowedLoss) < 0) {
            String reason = String.format(
                    "Daily loss circuit breaker tripped — PnL today: %.2f EUR (limit: %.2f EUR) [pair=%s interval=%s strategy=%s]",
                    dailyPnl, maxAllowedLoss, pair, interval, strategyType);
            log.warn("Risk rejected: {}", reason);
            return RiskValidationResult.rejected(reason);
        }

        // 3. Consecutive losses circuit breaker
        int consecutive = countConsecutiveLosses(pair, interval, strategyType);
        if (consecutive >= risk.getMaxConsecutiveLosses()) {
            String reason = String.format(
                    "Consecutive loss circuit breaker tripped — %d losses in a row (limit: %d) [pair=%s interval=%s strategy=%s]",
                    consecutive, risk.getMaxConsecutiveLosses(), pair, interval, strategyType);
            log.warn("Risk rejected: {}", reason);
            return RiskValidationResult.rejected(reason);
        }

        BigDecimal positionSize = availableBalance
                .multiply(risk.getMaxPositionPct())
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);

        log.info("Risk approved [pair={} interval={} strategy={}] — positionSize={}EUR openPositions={} dailyPnl={} consecutiveLosses={}",
                pair, interval, strategyType, positionSize, openPositions, dailyPnl, consecutive);
        return RiskValidationResult.approved(positionSize);
    }

    /**
     * Backward-compatible (pair, strategy) scoped validation — uses primary interval.
     */
    public RiskValidationResult validateForStrategy(BigDecimal availableBalance,
                                                    String pair,
                                                    StrategyType strategyType) {
        return validateForStrategy(availableBalance, pair, config.primaryInterval(), strategyType);
    }

    /**
     * Legacy global validation — uses the primary pair and interval, no strategy scope.
     */
    public RiskValidationResult validate(BigDecimal availableBalance) {
        return validateForStrategy(availableBalance, config.primaryPair(), null);
    }

    /**
     * Returns a risk snapshot scoped to a specific (pair, interval, strategy) — used by the trading loop
     * and the /api/strategies/{name} dashboard endpoints.
     */
    public RiskStatus currentStatusForStrategy(BigDecimal availableBalance,
                                               String pair,
                                               String interval,
                                               StrategyType strategyType) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        BigDecimal dailyPnl = tradeRepository
                .sumPnlSinceAndPairAndIntervalAndStrategy(startOfDay, pair, interval, strategyType);

        long openPositions = positionRepository
                .countByStatusAndPairAndIntervalAndStrategyName(OrderStatus.OPEN, pair, interval, strategyType);

        int consecutive = countConsecutiveLosses(pair, interval, strategyType);

        TradingConfig.Risk risk = config.getRisk();
        BigDecimal maxDailyLoss = availableBalance
                .multiply(risk.getMaxDailyLossPct())
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)
                .negate();

        boolean dailyLimitBreached       = dailyPnl.compareTo(maxDailyLoss) < 0;
        boolean consecutiveLimitBreached = consecutive >= risk.getMaxConsecutiveLosses();
        boolean positionLimitBreached    = openPositions >= risk.getMaxConcurrentPositions();

        return new RiskStatus(openPositions, dailyPnl, consecutive,
                dailyLimitBreached, consecutiveLimitBreached, positionLimitBreached);
    }

    /**
     * Backward-compatible (pair, strategy) scoped status — uses primary interval.
     */
    public RiskStatus currentStatusForStrategy(BigDecimal availableBalance,
                                               String pair,
                                               StrategyType strategyType) {
        return currentStatusForStrategy(availableBalance, pair, config.primaryInterval(), strategyType);
    }

    /**
     * Legacy global risk snapshot — uses the primary pair and interval, no strategy scope.
     */
    public RiskStatus currentStatus(BigDecimal availableBalance) {
        return currentStatusForStrategy(availableBalance, config.primaryPair(), null);
    }

    // ─── Per-cycle snapshot (§3.3) ────────────────────────────────────────────

    /**
     * Builds a full-cycle snapshot of risk metrics in just 3 DB round-trips
     * (open-position counts, today's PnL sums, recent trades for streaks),
     * all grouped by the (pair, interval, strategy) key.
     *
     * The trading loop calls this once per cycle and feeds it to
     * {@link #statusFromSnapshot(CycleSnapshot, BigDecimal, String, String, StrategyType)}
     * per signal — replacing ~3 DB queries per signal (up to ~540 reads/cycle
     * today with 180 signals) with 3 aggregate queries.
     */
    public CycleSnapshot snapshotAll() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();

        // Query 1: COUNT(*) open positions grouped by (pair, interval, strategy)
        Map<Key, Long> openPositions = new HashMap<>();
        for (Object[] row : positionRepository.countByStatusGroupedByPairIntervalStrategy(OrderStatus.OPEN)) {
            openPositions.put(keyOf(row), ((Number) row[3]).longValue());
        }

        // Query 2: SUM(pnl) today grouped by (pair, interval, strategy)
        Map<Key, BigDecimal> dailyPnls = new HashMap<>();
        for (Object[] row : tradeRepository.sumPnlSinceGroupedByPairIntervalStrategy(startOfDay)) {
            dailyPnls.put(keyOf(row), (BigDecimal) row[3]);
        }

        // Query 3: recent trades (bounded window) → consecutive-loss streaks in memory
        int maxStreak = config.getRisk().getMaxConsecutiveLosses();
        LocalDateTime streakWindowStart = startOfDay.minusDays(CONSECUTIVE_LOSS_WINDOW_DAYS);
        Map<Key, Integer> consecutiveLosses = new HashMap<>();
        Map<Key, Boolean> streakClosed = new HashMap<>();
        for (Trade trade : tradeRepository.findByExecutedAtAfterOrderByExecutedAtDesc(streakWindowStart)) {
            Key key = new Key(trade.getPair(), trade.getInterval(), trade.getStrategyName());
            if (Boolean.TRUE.equals(streakClosed.get(key))) {
                continue;
            }
            int current = consecutiveLosses.getOrDefault(key, 0);
            if (current >= maxStreak) {
                streakClosed.put(key, true);
                continue;
            }
            if (trade.getPnl() != null && trade.getPnl().compareTo(BigDecimal.ZERO) < 0) {
                consecutiveLosses.put(key, current + 1);
            } else {
                streakClosed.put(key, true);
            }
        }

        return new CycleSnapshot(openPositions, dailyPnls, consecutiveLosses);
    }

    /**
     * Computes a {@link RiskStatus} for a (pair, interval, strategy) using values from the
     * per-cycle snapshot — no DB access.
     */
    public RiskStatus statusFromSnapshot(CycleSnapshot snapshot,
                                         BigDecimal availableBalance,
                                         String pair,
                                         String interval,
                                         StrategyType strategyType) {
        Key key = new Key(pair, interval, strategyType);
        long openPositions = snapshot.openPositions().getOrDefault(key, 0L);
        BigDecimal dailyPnl = snapshot.dailyPnls().getOrDefault(key, BigDecimal.ZERO);
        int consecutive = snapshot.consecutiveLosses().getOrDefault(key, 0);

        TradingConfig.Risk risk = config.getRisk();
        BigDecimal maxDailyLoss = availableBalance
                .multiply(risk.getMaxDailyLossPct())
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)
                .negate();

        boolean dailyLimitBreached       = dailyPnl.compareTo(maxDailyLoss) < 0;
        boolean consecutiveLimitBreached = consecutive >= risk.getMaxConsecutiveLosses();
        boolean positionLimitBreached    = openPositions >= risk.getMaxConcurrentPositions();

        return new RiskStatus(openPositions, dailyPnl, consecutive,
                dailyLimitBreached, consecutiveLimitBreached, positionLimitBreached);
    }

    /** How far back to look when computing consecutive-loss streaks in the per-cycle snapshot. */
    private static final int CONSECUTIVE_LOSS_WINDOW_DAYS = 30;

    private static Key keyOf(Object[] row) {
        return new Key((String) row[0], (String) row[1], (StrategyType) row[2]);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private int countConsecutiveLosses(String pair, String interval, StrategyType strategyType) {
        int limit = config.getRisk().getMaxConsecutiveLosses();
        List<Trade> recent = tradeRepository
                .findRecentTradesByPairAndIntervalAndStrategy(pair, interval, strategyType, limit);

        int count = 0;
        for (Trade trade : recent) {
            if (trade.getPnl() != null && trade.getPnl().compareTo(BigDecimal.ZERO) < 0) {
                count++;
            } else {
                break;   // streak broken — stop counting
            }
        }
        return count;
    }

    /**
     * Read-only snapshot of the current risk metrics — returned by currentStatus().
     */
    public record RiskStatus(
            long openPositions,
            BigDecimal dailyPnl,
            int consecutiveLosses,
            boolean dailyCircuitBreakerTripped,
            boolean consecutiveCircuitBreakerTripped,
            boolean positionLimitReached
    ) {
        public boolean anyCircuitBreakerTripped() {
            return dailyCircuitBreakerTripped || consecutiveCircuitBreakerTripped;
        }
    }

    /** Composite key for the per-cycle snapshot — mirrors the (pair, interval, strategy) triple. */
    public record Key(String pair, String interval, StrategyType strategy) {}

    /**
     * Bulk-loaded risk metrics for every (pair, interval, strategy) that had activity
     * this cycle. Populated once per {@link #snapshotAll()} call and consumed per-signal
     * via {@link #statusFromSnapshot}.
     */
    public record CycleSnapshot(
            Map<Key, Long> openPositions,
            Map<Key, BigDecimal> dailyPnls,
            Map<Key, Integer> consecutiveLosses
    ) {}
}
