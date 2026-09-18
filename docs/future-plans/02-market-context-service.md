# A2 — MarketContextService [P0]

**Type:** Runtime Spring service
**Effort:** 1.5 days (Phase 1)
**Value:** High — monetizes two already-paid-for data sources
**Dependencies:** None

## Problem

Two valuable data sources are already fetched but completely unused for trading decisions:

1. **Fear & Greed Index** — `FearGreedService` fetches, caches, and exposes it on the dashboard, but `SignalEngine` ignores it when generating signals
2. **Order book** — `MarketDataClient.getOrderBook()` is only called by `TestController`, never by strategies or execution logic

Every strategy emits a fixed `confidence` (e.g. EMA Crossover BUY = 75) without any awareness of broader market conditions.

## Solution

A `MarketContextService` that aggregates all available context into a **confidence multiplier** (range 0.5 – 1.5) applied in `SignalEngine` after each strategy evaluation.

## New Files

| File | Purpose |
|------|---------|
| `market/MarketContextService.java` | Aggregates context, computes per-`(pair, interval)` multiplier |
| `market/MarketContext.java` | Record: F&G value, order book imbalance, computed multiplier |

## Integration Point

`strategy/SignalEngine.java:50-71` — the existing triple loop:

```java
public List<Signal> evaluateAllPairsAndPersist() {
    List<String> pairs = tradingConfig.getPairs();
    List<String> intervalLabels = tradingConfig.intervalLabels();
    List<Signal> allSignals = new ArrayList<>();

    for (String pair : pairs) {
        for (String intervalLabel : intervalLabels) {
            BarSeries series = marketDataService
                .fetchBarSeriesForPairAndInterval(pair, intervalLabel);

            // NEW — fetch context once per (pair, interval), not per strategy
            MarketContext ctx = marketContextService.getContext(pair, intervalLabel);

            for (TradingStrategy strategy : strategies) {
                Signal signal = strategy.evaluate(series, pair)
                    .withInterval(intervalLabel);

                // NEW — apply multiplier
                Signal adjusted = signal.withAdjustedConfidence(
                    signal.confidence().multiply(ctx.multiplier())
                );

                persist(adjusted);
                allSignals.add(adjusted);
            }
        }
    }
    return allSignals;
}
```

## Phase 1 — Existing Data Only (Zero New APIs)

### Fear & Greed Index Logic

`FearGreedService.get().value()` returns 0–100.

| F&G Range | Classification | BUY multiplier | SELL multiplier |
|-----------|---------------|----------------|-----------------|
| 0–19 | Extreme Fear | 0.80 | 1.20 |
| 20–39 | Fear | 0.90 | 1.10 |
| 40–59 | Neutral | 1.00 | 1.00 |
| 60–79 | Greed | 0.90 | 1.10 |
| 80–100 | Extreme Greed | 0.70 | 1.30 |

Rationale: extreme sentiment readings are dangerous on both sides. In Extreme Greed, markets are near local tops — dampening BUYs and amplifying SELLs protects capital. In Extreme Fear, volatility spikes — slight BUY dampening avoids catching falling knives.

### Order Book Imbalance Logic

Fetch top-5 bid/ask levels via `MarketDataClient.getOrderBook(pair, 5)`:

```java
BigDecimal totalBidVolume = bids.stream().map(Level::q).reduce(BigDecimal.ZERO, BigDecimal::add);
BigDecimal totalAskVolume = asks.stream().map(Level::q).reduce(BigDecimal.ZERO, BigDecimal::add);
BigDecimal ratio = totalBidVolume.divide(totalAskVolume, 4, HALF_UP);
```

| Ratio | Interpretation | BUY multiplier | SELL multiplier |
|-------|---------------|----------------|-----------------|
| > 1.5 | Heavy bid pressure | 1.15 | 0.90 |
| 0.67 – 1.5 | Balanced | 1.00 | 1.00 |
| < 0.67 | Heavy ask pressure | 0.90 | 1.15 |

### Combining Multipliers

```java
BigDecimal fgMultiplier = fearGreedMultiplierFor(signal.type(), fgValue);
BigDecimal obMultiplier = orderBookMultiplierFor(signal.type(), bidAskRatio);
BigDecimal combined = fgMultiplier.multiply(obMultiplier);
// Clamp to [0.5, 1.5] to prevent extreme amplification
BigDecimal clamped = combined.max(new BigDecimal("0.5")).min(new BigDecimal("1.5"));
```

## Modifications to Existing Files

| File | Change |
|------|--------|
| `strategy/SignalEngine.java` | Inject `MarketContextService`; apply multiplier in triple loop (lines 50–71) |
| `strategy/Signal.java` | Add `withAdjustedConfidence(BigDecimal)` copy method alongside existing `withInterval()` |
| `model/entity/SignalLog.java` | Add optional `adjusted_confidence` column |

## New Flyway Migration

`src/main/resources/db/migration/V6__add_adjusted_confidence.sql`:

```sql
ALTER TABLE trading.signal_logs
  ADD COLUMN adjusted_confidence DECIMAL(5,2);

CREATE INDEX idx_signal_logs_adjusted_confidence
  ON trading.signal_logs (adjusted_confidence DESC);
```

## Caching Strategy

`MarketContextService` maintains its own short-lived cache to avoid repeated order book calls within a single cycle:

- Fear & Greed: reuse `FearGreedService` cache (1-hour TTL — already cached)
- Order book: fetch once per `(pair, interval)` per cycle (cache for 30 seconds to match trading loop frequency)

## Verification

1. `mvn test` — existing tests pass (additive changes only)
2. Inspect `signal_logs` after one cycle — `adjusted_confidence` column populated for every row
3. Mock `FearGreedService.get()` to return an Extreme Fear value — verify BUY signals get 0.80x multiplier in logs
4. Mock order book with 10x bid-skewed depth — verify BUY signals get additional 1.15x boost
5. Verify clamping — impossible multipliers (e.g. 0.9 * 0.8 * 0.85 = 0.612) stay within [0.5, 1.5]

## Future Extensions

Phase 2 of `MarketContextService` (covered in later documents) adds:
- Higher-timeframe trend alignment ([`04-cross-interval-confirmation.md`](./04-cross-interval-confirmation.md))
- BTC dominance trend ([`06-coingecko-global-metrics.md`](./06-coingecko-global-metrics.md))
- News sentiment ([`10-news-sentiment.md`](./10-news-sentiment.md))
- On-chain flows ([`11-onchain-metrics.md`](./11-onchain-metrics.md))

All future inputs plug into the same `MarketContext` record without changing the `SignalEngine` integration point.
