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
import java.util.List;

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
     * Scoped to the given (pair, strategy) — circuit breakers are fully isolated.
     *
     * @param availableBalance paper balance for this (pair, strategy) combination
     * @param pair             trading pair (e.g. "BTC-EUR")
     * @param strategyType     strategy to scope the checks to
     */
    public RiskValidationResult validateForStrategy(BigDecimal availableBalance,
                                                    String pair,
                                                    StrategyType strategyType) {
        TradingConfig.Risk risk = config.getRisk();

        // 1. Concurrent positions cap
        long openPositions = strategyType != null
                ? positionRepository.countByStatusAndPairAndStrategyName(OrderStatus.OPEN, pair, strategyType)
                : positionRepository.countByStatus(OrderStatus.OPEN);

        if (openPositions >= risk.getMaxConcurrentPositions()) {
            String reason = String.format("Max concurrent positions reached (%d/%d) [pair=%s strategy=%s]",
                    openPositions, risk.getMaxConcurrentPositions(), pair, strategyType);
            log.warn("Risk rejected: {}", reason);
            return RiskValidationResult.rejected(reason);
        }

        // 2. Daily loss circuit breaker
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        BigDecimal dailyPnl = strategyType != null
                ? tradeRepository.sumPnlSinceAndPairAndStrategy(startOfDay, pair, strategyType)
                : tradeRepository.sumPnlSince(startOfDay);

        BigDecimal maxAllowedLoss = availableBalance
                .multiply(risk.getMaxDailyLossPct())
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)
                .negate();

        if (dailyPnl.compareTo(maxAllowedLoss) < 0) {
            String reason = String.format(
                    "Daily loss circuit breaker tripped — PnL today: %.2f EUR (limit: %.2f EUR) [pair=%s strategy=%s]",
                    dailyPnl, maxAllowedLoss, pair, strategyType);
            log.warn("Risk rejected: {}", reason);
            return RiskValidationResult.rejected(reason);
        }

        // 3. Consecutive losses circuit breaker
        int consecutive = countConsecutiveLosses(pair, strategyType);
        if (consecutive >= risk.getMaxConsecutiveLosses()) {
            String reason = String.format(
                    "Consecutive loss circuit breaker tripped — %d losses in a row (limit: %d) [pair=%s strategy=%s]",
                    consecutive, risk.getMaxConsecutiveLosses(), pair, strategyType);
            log.warn("Risk rejected: {}", reason);
            return RiskValidationResult.rejected(reason);
        }

        BigDecimal positionSize = availableBalance
                .multiply(risk.getMaxPositionPct())
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);

        log.info("Risk approved [pair={} strategy={}] — positionSize={}EUR openPositions={} dailyPnl={} consecutiveLosses={}",
                pair, strategyType, positionSize, openPositions, dailyPnl, consecutive);
        return RiskValidationResult.approved(positionSize);
    }

    /**
     * Legacy global validation — uses the primary pair, no strategy scope.
     * Kept for backward-compatible callers.
     */
    public RiskValidationResult validate(BigDecimal availableBalance) {
        return validateForStrategy(availableBalance, config.primaryPair(), null);
    }

    /**
     * Returns a risk snapshot scoped to a specific (pair, strategy) — used by the trading loop
     * and the /api/strategies/{name} dashboard endpoints.
     */
    public RiskStatus currentStatusForStrategy(BigDecimal availableBalance,
                                               String pair,
                                               StrategyType strategyType) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        BigDecimal dailyPnl = strategyType != null
                ? tradeRepository.sumPnlSinceAndPairAndStrategy(startOfDay, pair, strategyType)
                : tradeRepository.sumPnlSince(startOfDay);

        long openPositions = strategyType != null
                ? positionRepository.countByStatusAndPairAndStrategyName(OrderStatus.OPEN, pair, strategyType)
                : positionRepository.countByStatus(OrderStatus.OPEN);

        int consecutive = countConsecutiveLosses(pair, strategyType);

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
     * Legacy global risk snapshot — uses the primary pair, no strategy scope.
     * Kept for the legacy /api/status endpoint.
     */
    public RiskStatus currentStatus(BigDecimal availableBalance) {
        return currentStatusForStrategy(availableBalance, config.primaryPair(), null);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private int countConsecutiveLosses(String pair, StrategyType strategyType) {
        int limit = config.getRisk().getMaxConsecutiveLosses();
        List<Trade> recent = strategyType != null
                ? tradeRepository.findRecentTradesByPairAndStrategy(pair, strategyType, limit)
                : tradeRepository.findRecentTradesByPair(pair, limit);

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
}
