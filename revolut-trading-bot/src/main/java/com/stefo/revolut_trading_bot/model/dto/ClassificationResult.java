package com.stefo.revolut_trading_bot.model.dto;

import java.math.BigDecimal;

/**
 * One classified Reddit post. Returned by {@code SentimentClassifier.classify(...)}.
 *
 * @param externalId Reddit post id (same as the DB's {@code external_id} column)
 * @param score      sentiment in [-1.000, +1.000]; negative = bearish, positive = bullish
 * @param reason     one short sentence of rationale — stored in {@code sentiment_snapshots.metadata}
 */
public record ClassificationResult(
        String externalId,
        BigDecimal score,
        String reason
) {}
