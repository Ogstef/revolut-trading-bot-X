# B1 — Cross-Interval Confirmation [P1]

**Type:** Runtime Spring service (extension of MarketContextService)
**Effort:** 1 day
**Value:** High — best risk-adjusted ROI of the entire plan
**Dependencies:** A2 (MarketContextService)

## Important Clarification

This is **NOT** "add multi-timeframe support" — Phase 11 already shipped that as full execution units. Each `(pair, strategy, interval)` triple runs as a fully isolated virtual portfolio.

This is about **using one interval as a CONFIRMATION FILTER for another interval's signals** — dampening signals that conflict with the higher-timeframe trend.

## Problem

Today, each `(pair, interval, strategy)` portfolio runs in isolation. Consider this scenario:

- 15m EMA_CROSSOVER on BTC-EUR fires a BUY signal
- Meanwhile, the 1h trend on BTC-EUR is clearly bearish
- The 15m portfolio blindly opens a position against the higher-timeframe trend

Signals against higher-timeframe trends have historically lower win rates. A smart filter would dampen these.

## Solution

Extend `MarketContextService` to query the cached `BarSeries` from OTHER intervals when computing the context multiplier. **Zero new API calls** — Phase 11's `MarketDataService.barSeriesMap` already holds all `(pair, interval)` series in memory after each cycle.

## Logic

For a signal on interval `I`, look up the **next higher interval** in `TradingConfig.intervals`:

- 15m signal → check 1h cached BarSeries
- 1h signal → check 4h cached BarSeries (if configured; currently only 15m + 1h are live)
- Highest configured interval → no higher-timeframe check (multiplier = 1.0)

Compute the higher-timeframe trend using a simple, well-understood indicator:

```java
BarSeries htfSeries = marketDataService.getBarSeriesForPairAndInterval(pair, htfLabel);
EMAIndicator emaHtf = new EMAIndicator(new ClosePriceIndicator(htfSeries), 21);
int lastIdx = htfSeries.getEndIndex();

BigDecimal price = toBig(htfSeries.getBar(lastIdx).getClosePrice());
BigDecimal ema   = toBig(emaHtf.getValue(lastIdx));
BigDecimal emaPrev = toBig(emaHtf.getValue(lastIdx - 3)); // 3 bars back

HtfTrend trend;
if (price.compareTo(ema) > 0 && ema.compareTo(emaPrev) > 0) {
    trend = HtfTrend.BULLISH;
} else if (price.compareTo(ema) < 0 && ema.compareTo(emaPrev) < 0) {
    trend = HtfTrend.BEARISH;
} else {
    trend = HtfTrend.NEUTRAL;
}
```

## Multiplier Table

| Signal type | HTF trend | Multiplier |
|-------------|-----------|------------|
| BUY | BULLISH | 1.20 (boost — aligned) |
| BUY | NEUTRAL | 1.00 |
| BUY | BEARISH | 0.70 (dampen — fighting HTF) |
| SELL | BULLISH | 0.70 (dampen — fighting HTF) |
| SELL | NEUTRAL | 1.00 |
| SELL | BEARISH | 1.20 (boost — aligned) |
| HOLD | * | 1.00 |

## Modifications

### `market/MarketContextService.java`

Add method:

```java
public HtfTrend assessHigherTimeframeAlignment(String pair, String currentInterval) {
    String htfLabel = findNextHigherInterval(currentInterval);
    if (htfLabel == null) {
        return HtfTrend.NEUTRAL; // no higher TF configured
    }

    BarSeries htfSeries = marketDataService
        .getBarSeriesForPairAndInterval(pair, htfLabel);

    if (htfSeries == null || htfSeries.getBarCount() < 24) {
        return HtfTrend.NEUTRAL; // not enough data
    }

    return computeTrend(htfSeries);
}

private String findNextHigherInterval(String currentLabel) {
    int currentMinutes = tradingConfig.intervalMinutes(currentLabel);
    return tradingConfig.getIntervals().stream()
        .filter(m -> m > currentMinutes)
        .min(Integer::compareTo)
        .map(TradingConfig::intervalLabel)
        .orElse(null);
}
```

### New enum

`market/HtfTrend.java`:

```java
public enum HtfTrend { BULLISH, BEARISH, NEUTRAL }
```

### `market/MarketContext.java`

Extend the record:

```java
public record MarketContext(
    int fearGreedValue,
    BigDecimal orderBookRatio,
    HtfTrend higherTimeframeTrend,       // NEW
    BigDecimal multiplier
) {}
```

## Edge Cases

- **Highest interval in config:** no higher TF exists → return `NEUTRAL` → multiplier = 1.0
- **Insufficient bars:** if the HTF cache has < 24 bars (EMA(21) warmup + buffer) → return `NEUTRAL`
- **Cache miss:** `getBarSeriesForPairAndInterval` returns null → return `NEUTRAL` (never fetch synchronously — don't slow down the 30s heartbeat)

## Verification

1. Seed 1h BarSeries cache with clearly bullish data (rising candles)
2. Fire a 15m BUY signal for the same pair
3. Verify log shows HTF trend = BULLISH and multiplier includes 1.20 boost
4. Repeat with bearish 1h data → verify 0.70 dampening
5. Test with no 1h data configured → verify NEUTRAL (no crash)
6. Compare signal-to-trade conversion rate before/after feature enables

## Expected Impact

Based on general technical analysis research, HTF-aligned signals have ~10–15% higher win rates than counter-trend signals. This translates to:

- Fewer trades (some will be filtered/dampened below execution threshold)
- Higher average win rate on remaining trades
- Lower drawdown (catastrophic losses often occur when fighting HTF trend)

## Follow-ups

Could extend further:
- Use TWO higher timeframes (e.g. 15m signal checks both 1h AND 4h)
- Require HTF trend AND price position above HTF moving average
- Penalize even harder if HTF is showing extreme readings (RSI > 80 on 1h + 15m BUY = likely exhaustion)
