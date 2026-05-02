package com.stefo.revolut_trading_bot.market;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Macro market-context snapshot for one trading pair.
 *
 * Aggregates two already-fetched signals that no other strategy reads today:
 *   - Fear & Greed Index (interval-agnostic; daily macro sentiment 0–100)
 *   - Top-of-book bid/ask volume ratio (live microstructure pressure)
 *
 * Either field may be null when its upstream fetch fails — callers must treat
 * a missing input as "no signal" rather than a default value, so a flaky
 * external API never propagates as a fake trade signal.
 */
public record MarketContext(
        String pair,
        Integer fearGreedValue,
        String fearGreedClassification,
        BigDecimal bidAskRatio,
        Instant snapshotAt
) {}
