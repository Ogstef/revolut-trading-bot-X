package com.stefo.revolut_trading_bot.strategy;

import com.stefo.revolut_trading_bot.model.enums.SignalType;

import java.math.BigDecimal;

/**
 * Immutable snapshot of one signal evaluation.
 *
 * Contains the decision (BUY/SELL/HOLD) plus every indicator value
 * that produced it, so callers can log or persist the full picture.
 */
public record Signal(
        SignalType type,
        BigDecimal confidence,   // 0 – 100; higher = stronger conviction
        String reason,
        BigDecimal emaShort,
        BigDecimal emaLong,
        BigDecimal rsi,
        BigDecimal currentPrice
) {}
