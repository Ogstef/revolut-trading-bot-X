package com.stefo.revolut_trading_bot.strategy;

import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable snapshot of one signal evaluation.
 *
 * Contains the decision (BUY/SELL/HOLD), context (pair, interval, when it was evaluated),
 * and every indicator value that produced it — so callers can log or persist
 * the full picture without needing to re-derive anything.
 *
 * Strategies construct this with interval=null (they are interval-agnostic).
 * The SignalEngine injects the interval via {@link #withInterval(String)} after evaluation.
 */
public record Signal(
        SignalType type,
        BigDecimal confidence,   // 0 – 100; higher = stronger conviction
        String reason,
        String pair,             // e.g. "BTC-EUR" — which market this signal is for
        String interval,         // e.g. "15m", "1h" — which candle interval produced this signal
        StrategyType strategyType,   // which strategy produced this signal
        Instant evaluatedAt,     // when the signal was computed (UTC)
        BigDecimal emaShort,     // repurposed per-strategy: MACD line / upper band / null
        BigDecimal emaLong,      // repurposed per-strategy: signal line / lower band / null
        BigDecimal rsi,          // repurposed per-strategy: histogram / %B / RSI value
        BigDecimal currentPrice
) {
    /**
     * Returns a copy of this signal with the given interval injected.
     * Used by SignalEngine to tag strategy-produced signals with their interval context.
     */
    public Signal withInterval(String interval) {
        return new Signal(type, confidence, reason, pair, interval, strategyType,
                evaluatedAt, emaShort, emaLong, rsi, currentPrice);
    }
}
