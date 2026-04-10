package com.stefo.revolut_trading_bot.risk;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
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
 * If all checks pass, returns the approved position size (maxPositionPct% of balance).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RiskManager {

    private final PositionRepository positionRepository;
    private final TradeRepository tradeRepository;
    private final TradingConfig config;

    /**
     * Validates whether a new trade is allowed given the current portfolio state.
     *
     * @param availableBalance  current EUR balance (real or paper)
     */
    public RiskValidationResult validate(BigDecimal availableBalance) {
        TradingConfig.Risk risk = config.getRisk();

        // 1. Concurrent positions cap
        long openPositions = positionRepository.countByStatus(OrderStatus.OPEN);
        if (openPositions >= risk.getMaxConcurrentPositions()) {
            String reason = String.format("Max concurrent positions reached (%d/%d)",
                    openPositions, risk.getMaxConcurrentPositions());
            log.warn("Risk rejected: {}", reason);
            return RiskValidationResult.rejected(reason);
        }

        // 2. Daily loss circuit breaker
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        BigDecimal dailyPnl = tradeRepository.sumPnlSince(startOfDay);
        BigDecimal maxAllowedLoss = availableBalance
                .multiply(risk.getMaxDailyLossPct())
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)
                .negate();   // negative = a loss threshold

        if (dailyPnl.compareTo(maxAllowedLoss) < 0) {
            String reason = String.format("Daily loss circuit breaker tripped — PnL today: %.2f EUR (limit: %.2f EUR)",
                    dailyPnl, maxAllowedLoss);
            log.warn("Risk rejected: {}", reason);
            return RiskValidationResult.rejected(reason);
        }

        // 3. Consecutive losses circuit breaker
        int consecutive = countConsecutiveLosses();
        if (consecutive >= risk.getMaxConsecutiveLosses()) {
            String reason = String.format("Consecutive loss circuit breaker tripped — %d losses in a row (limit: %d)",
                    consecutive, risk.getMaxConsecutiveLosses());
            log.warn("Risk rejected: {}", reason);
            return RiskValidationResult.rejected(reason);
        }

        // All checks passed — calculate position size
        BigDecimal positionSize = availableBalance
                .multiply(risk.getMaxPositionPct())
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);

        log.info("Risk approved — positionSize={}EUR openPositions={} dailyPnl={} consecutiveLosses={}",
                positionSize, openPositions, dailyPnl, consecutive);
        return RiskValidationResult.approved(positionSize);
    }

    /**
     * Returns a snapshot of current risk metrics for monitoring/debugging.
     */
    public RiskStatus currentStatus(BigDecimal availableBalance) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        BigDecimal dailyPnl = tradeRepository.sumPnlSince(startOfDay);
        long openPositions = positionRepository.countByStatus(OrderStatus.OPEN);
        int consecutive = countConsecutiveLosses();

        TradingConfig.Risk risk = config.getRisk();
        BigDecimal maxDailyLoss = availableBalance
                .multiply(risk.getMaxDailyLossPct())
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)
                .negate();

        boolean dailyLimitBreached = dailyPnl.compareTo(maxDailyLoss) < 0;
        boolean consecutiveLimitBreached = consecutive >= risk.getMaxConsecutiveLosses();
        boolean positionLimitBreached = openPositions >= risk.getMaxConcurrentPositions();

        return new RiskStatus(openPositions, dailyPnl, consecutive,
                dailyLimitBreached, consecutiveLimitBreached, positionLimitBreached);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private int countConsecutiveLosses() {
        // Fetch the last N trades (N = consecutive loss limit) and count from most recent
        int limit = config.getRisk().getMaxConsecutiveLosses();
        List<Trade> recent = tradeRepository.findRecentTradesByPair(config.getPair(), limit);

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
