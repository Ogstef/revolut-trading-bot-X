package com.stefo.revolut_trading_bot.strategy;

import com.stefo.revolut_trading_bot.model.enums.SignalType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable snapshot of one signal evaluation.
 *
 * Contains the decision (BUY/SELL/HOLD), context (pair, when it was evaluated),
 * and every indicator value that produced it — so callers can log or persist
 * the full picture without needing to re-derive anything.
 */
public record Signal(
        SignalType type,
        BigDecimal confidence,   // 0 – 100; higher = stronger conviction
        String reason,
        String pair,             // e.g. "BTC-EUR" — which market this signal is for
        Instant evaluatedAt,     // when the signal was computed (UTC)
        BigDecimal emaShort,
        BigDecimal emaLong,
        BigDecimal rsi,
        BigDecimal currentPrice
) {}
