package com.stefo.revolut_trading_bot.strategy.impl;

import com.stefo.revolut_trading_bot.model.dto.SentimentScore;
import com.stefo.revolut_trading_bot.model.enums.SentimentSource;
import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.strategy.Signal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Shared Signal-construction logic for the three sentiment strategies.
 *
 * Keeps the Signal record's {@code emaShort / emaLong / rsi} slots consistent
 * across strategies so the UI can render them uniformly:
 * <ul>
 *   <li>{@code emaShort} → the aggregate sentiment score ({@code -1…+1})</li>
 *   <li>{@code emaLong}  → the window's engagement volume (sum of upvotes / votes)</li>
 *   <li>{@code rsi}      → sample size; for COMBINED, 0 = sources agree, 1 = disagree</li>
 * </ul>
 */
final class SentimentSignalBuilder {

    private SentimentSignalBuilder() {}

    /** Build a BUY/SELL/HOLD Signal from a computed {@link SentimentScore}. */
    static Signal build(SentimentScore score,
                        StrategyType strategyType,
                        BigDecimal buyThreshold,
                        BigDecimal sellThreshold,
                        boolean requireAgreement,
                        BigDecimal currentPrice,
                        Instant now) {

        if (!score.hasSignal()) {
            return hold(
                    score, strategyType,
                    "insufficient %s sample (n=%d / min=%d)".formatted(
                            score.source(), score.sampleSize(), score.minSample()),
                    currentPrice, now);
        }

        if (requireAgreement && !score.componentsAgree()) {
            return hold(
                    score, strategyType,
                    disagreementReason(score),
                    currentPrice, now);
        }

        SignalType type = classify(score.score(), buyThreshold, sellThreshold);
        String reason = buildReason(score, type, buyThreshold, sellThreshold);
        BigDecimal confidence = confidenceFromScore(score.score());

        return new Signal(
                type,
                confidence,
                reason,
                score.pair(),
                null,                              // interval injected by SignalEngine
                strategyType,
                now,
                score.score(),                     // emaShort slot → score
                BigDecimal.valueOf(score.volume()),// emaLong slot → volume
                decideRsiSlot(score),              // rsi slot → sample size / disagreement flag
                currentPrice
        );
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private static SignalType classify(BigDecimal score, BigDecimal buy, BigDecimal sell) {
        if (score.compareTo(buy)  >  0) return SignalType.BUY;
        if (score.compareTo(sell) <  0) return SignalType.SELL;
        return SignalType.HOLD;
    }

    private static BigDecimal confidenceFromScore(BigDecimal score) {
        BigDecimal magnitude = score.abs();
        if (magnitude.compareTo(BigDecimal.ONE) > 0) magnitude = BigDecimal.ONE;
        // 50 + 50 * |score|  → [50, 100] for signals with data
        return BigDecimal.valueOf(50)
                .add(magnitude.multiply(BigDecimal.valueOf(50)))
                .setScale(0, RoundingMode.HALF_UP);
    }

    private static Signal hold(SentimentScore score, StrategyType type, String reason,
                               BigDecimal currentPrice, Instant now) {
        return new Signal(
                SignalType.HOLD,
                BigDecimal.valueOf(50),
                reason,
                score.pair(),
                null,
                type,
                now,
                score.score(),       // may be null — Signal tolerates
                BigDecimal.valueOf(score.volume()),
                decideRsiSlot(score),
                currentPrice
        );
    }

    private static BigDecimal decideRsiSlot(SentimentScore score) {
        if (score.source() == SentimentSource.COMBINED) {
            return score.componentsAgree() ? BigDecimal.ZERO : BigDecimal.ONE;
        }
        return BigDecimal.valueOf(score.sampleSize());
    }

    private static String buildReason(SentimentScore score, SignalType type,
                                      BigDecimal buy, BigDecimal sell) {
        String base = "%s score %s over last %dm (n=%d, volume=%d)".formatted(
                score.source(),
                score.score().toPlainString(),
                score.windowMinutes(),
                score.sampleSize(),
                score.volume());
        return switch (type) {
            case BUY  -> base + " — crossed buy threshold " + buy.toPlainString();
            case SELL -> base + " — crossed sell threshold " + sell.toPlainString();
            case HOLD -> base + " — inside neutral band (buy=" + buy + ", sell=" + sell + ")";
        };
    }

    private static String disagreementReason(SentimentScore score) {
        SentimentScore reddit = score.findComponent(SentimentSource.REDDIT);
        SentimentScore crypto = score.findComponent(SentimentSource.CRYPTOPANIC);
        String r = reddit != null && reddit.score() != null ? reddit.score().toPlainString() : "n/a";
        String c = crypto != null && crypto.score() != null ? crypto.score().toPlainString() : "n/a";
        return "sources disagree: reddit=%s cryptopanic=%s".formatted(r, c);
    }
}
