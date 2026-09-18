# A3 — Backtest Engine & REST API [P0]

**Type:** Runtime Spring service + REST API
**Effort:** 3–4 days
**Value:** Critical — foundation for everything else (risk analytics, optimization, MCP wrapper)
**Dependencies:** None (uses existing Phase 11 schema)

## Purpose

Replay historical candles through strategies to answer questions like:

- "Would `(BTC-EUR, ICHIMOKU, 1h)` have made money last month?"
- "Which of the 72 virtual portfolios have positive expectancy?"
- "If we tuned EMA_CROSSOVER's periods from 9/21 to 7/26, would results improve?"

This is the single most impactful addition — without backtesting, going LIVE is gambling.

## Architecture

Two layers:

1. **Spring Boot REST API** (this document) — the compute engine
2. **Custom MCP wrapper** ([`09-custom-backtest-mcp.md`](./09-custom-backtest-mcp.md)) — exposes the API to Claude Code

## New Package

`com.stefo.revolut_trading_bot.backtest`

## New Files

| File | Purpose |
|------|---------|
| `backtest/BacktestService.java` | Core engine — replays candles through strategies |
| `backtest/BacktestRequest.java` | Record: `{strategy, pair, interval, startDate, endDate, paramOverrides?}` |
| `backtest/BacktestResult.java` | Record: trades list, equity curve, `TradingStats` |
| `backtest/EquityCurvePoint.java` | Record: `{timestamp, equity, drawdown}` |
| `backtest/BacktestController.java` | REST endpoints |

## Reuse Existing Code (Low Risk)

| Existing component | How reused |
|--------------------|------------|
| `CandlestickRepository.findByPairAndIntervalOrderByTimestampAsc()` | Historical candle loading (Phase 11 interval-aware) |
| `MarketDataService.buildBarSeriesFromCandles()` pattern | Build `BarSeries` from loaded candles |
| `TradingStrategy.evaluate(BarSeries, String)` | Stateless — same code paths as production |
| `Signal.withInterval()` | Inject interval into backtest signals |
| `TakeProfitStopLossManager` | Simulate TP/SL exits |
| `TradingStats`, `TradeService` | Compute win rate, expectancy, profit factor |

Because strategies are stateless and interval-agnostic, backtesting uses the **exact same code** that runs live. No risk of "backtest vs production divergence".

## Core Algorithm

```java
public BacktestResult run(BacktestRequest request) {
    // 1. Load historical candles for the interval
    List<Candlestick> candles = candlestickRepo
        .findByPairAndIntervalOrderByTimestampAsc(
            request.pair(),
            intervalLabel(request.interval()));

    // Filter to date range
    candles = candles.stream()
        .filter(c -> !c.getTimestamp().isBefore(request.startDate())
                  && !c.getTimestamp().isAfter(request.endDate()))
        .toList();

    // 2. Get strategy bean (or instantiate with override params)
    TradingStrategy strategy = resolveStrategy(request);

    // 3. Walk forward bar-by-bar
    List<SimulatedTrade> trades = new ArrayList<>();
    List<EquityCurvePoint> equity = new ArrayList<>();
    SimulatedPosition openPos = null;
    BigDecimal balance = startingBalance;

    for (int i = minBarsForStrategy(strategy); i < candles.size(); i++) {
        BarSeries subSeries = buildSubSeries(candles, i);
        Bar currentBar = subSeries.getLastBar();

        // Check TP/SL on open position first
        if (openPos != null) {
            ExitCheck exit = checkExit(openPos, currentBar);
            if (exit.shouldExit()) {
                trades.add(closeTrade(openPos, exit, currentBar));
                balance = balance.add(exit.pnl());
                openPos = null;
            }
        }

        // Evaluate strategy
        Signal signal = strategy.evaluate(subSeries, request.pair())
            .withInterval(intervalLabel(request.interval()));

        if (signal.type() == BUY && openPos == null) {
            openPos = openPosition(signal, currentBar, balance);
        } else if (signal.type() == SELL && openPos != null) {
            trades.add(closeTrade(openPos, ExitReason.SIGNAL_EXIT, currentBar));
            balance = balance.add(openPos.pnl(currentBar.getClosePrice()));
            openPos = null;
        }

        equity.add(new EquityCurvePoint(
            currentBar.getEndTime(),
            balance,
            drawdownFromPeak(equity, balance)
        ));
    }

    return new BacktestResult(
        request,
        trades,
        equity,
        computeStats(trades)
    );
}
```

## REST Endpoints

### Run a single backtest

```
POST /api/backtest/run
Content-Type: application/json

{
  "strategy": "ICHIMOKU",
  "pair": "BTC-EUR",
  "interval": 60,
  "startDate": "2026-03-01T00:00:00",
  "endDate": "2026-04-01T00:00:00",
  "paramOverrides": null
}
```

Response:
```json
{
  "request": { ... },
  "trades": [
    { "entry": "...", "exit": "...", "pnl": 45.20, "pnlPct": 0.0452,
      "exitReason": "TP_HIT" }
  ],
  "equityCurve": [
    { "timestamp": "...", "equity": 10045.20, "drawdown": 0.0 }
  ],
  "stats": {
    "totalTrades": 18,
    "winRate": 0.611,
    "totalPnl": 412.40,
    "avgPnl": 22.91,
    "profitFactor": 2.3,
    "maxDrawdown": 85.20,
    "maxDrawdownPct": 0.008,
    "expectancy": 22.91
  }
}
```

### Compare intervals for the same strategy/pair

```
POST /api/backtest/compare-intervals
{
  "strategy": "ADX_DI",
  "pair": "ETH-EUR",
  "intervals": [15, 60, 240],
  "startDate": "2026-03-01T00:00:00",
  "endDate": "2026-04-01T00:00:00"
}
```

Returns ranked results — directly answers "is 15m or 1h better for ADX_DI on ETH-EUR?"

### Compare strategies on the same data

```
POST /api/backtest/compare-strategies
{
  "pair": "BTC-EUR",
  "interval": 60,
  "startDate": "2026-03-01T00:00:00",
  "endDate": "2026-04-01T00:00:00"
}
```

Returns all 12 strategies ranked by expectancy/profit factor.

## Parameter Overrides

For testing "what if EMA periods were 7/26 instead of 9/21":

```json
"paramOverrides": {
  "emaShortPeriod": 7,
  "emaLongPeriod": 26
}
```

Implementation: create a new strategy instance with a modified `TradingConfig` rather than mutating the Spring singleton. Because strategies take `TradingConfig` via constructor injection, this is clean.

```java
TradingConfig override = tradingConfig.toBuilder()
    .strategy(tradingConfig.getStrategy().toBuilder()
        .emaShortPeriod(7)
        .emaLongPeriod(26)
        .build())
    .build();
TradingStrategy strategy = new EmaCrossoverStrategy(override);
```

## Starting Balance

Use the configured paper balance for `(pair, strategy)` from `TradingConfig.strategyBalances` — ensures backtest mirrors live paper balance exactly.

## Output — Equity Curve

Essential for visualizing drawdown. Each point = `{timestamp, equity, drawdownFromPeak}`. The UI (or Claude Code via MCP) can chart this to answer "what's the worst drawdown period?"

## Verification

1. `POST /api/backtest/run` for `(BTC-EUR, EMA_CROSSOVER, 15m)` over the last 30 days of stored candles
2. Compare backtest trade count vs actual paper trades for the same period — same order of magnitude
3. Run on 1h interval — verify Phase 11 interval-aware queries return correct data
4. Pass `paramOverrides` with wildly different values — verify results change materially
5. Run the same backtest twice — results MUST be identical (determinism check)
6. Check the equity curve never has gaps (one point per bar after warmup)

## Known Limitations

- No slippage modeling — trades execute at bar close price
- No fee modeling — add later with `tradingConfig.fees.percent`
- No volume constraints — assumes infinite liquidity (fine for paper-scale positions)
- Historical candle coverage is whatever exists in DB — backtest range is limited by when the bot first ran

## Follow-ups

- [`05-risk-analytics.md`](./05-risk-analytics.md) — Sharpe, VaR, Sortino on the backtest output
- [`07-parameter-optimizer.md`](./07-parameter-optimizer.md) — grid search over `paramOverrides`
- [`08-correlation-service.md`](./08-correlation-service.md) — correlation between equity curves
- [`09-custom-backtest-mcp.md`](./09-custom-backtest-mcp.md) — expose these REST endpoints to Claude Code
