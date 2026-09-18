# C1 — Parameter Optimizer [P1]

**Type:** Runtime Spring service
**Effort:** 2 days
**Value:** High — finds better-than-default parameters per `(pair, interval, strategy)`
**Dependencies:** A3 (Backtest engine)

## Purpose

Today, all 12 strategies use hard-coded parameters (EMA 9/21, RSI period 14, etc.) for every pair and every interval. But BTC-EUR and SOL-EUR have wildly different volatility profiles, and 15m vs 1h has different noise characteristics. The same EMA periods that work on BTC 15m may be awful on SOL 1h.

The optimizer searches for parameters that would have worked best in the recent past for each `(pair, interval, strategy)` combination.

## New Files

| File | Purpose |
|------|---------|
| `backtest/ParameterOptimizer.java` | Grid search + walk-forward validation |
| `backtest/ParameterRanges.java` | Record defining search space per strategy |
| `backtest/OptimizationResult.java` | Record: ranked parameter sets with their stats |

## Search Strategies

### Grid Search (simple, deterministic)

Exhaustively tries every combination from specified ranges:

```java
paramRanges = ParameterRanges.emaCrossover(
    emaShortPeriod: List.of(5, 7, 9, 12, 15),
    emaLongPeriod:  List.of(15, 21, 26, 34, 50),
    rsiPeriod:      List.of(7, 14, 21),
    rsiOverbought:  List.of(65, 70, 75, 80),
    rsiOversold:    List.of(20, 25, 30, 35)
);
// = 5 × 5 × 3 × 4 × 4 = 1,200 backtests
```

For 12 strategies each with ~1,000 combinations per `(pair, interval)`: **864,000 backtests total** for full grid search across all 72 portfolios. Hours of compute — needs parallelization.

### Walk-Forward Validation (preferred)

Prevents overfitting by separating optimization data from validation data:

1. Split historical candles into rolling `(train, test)` windows — e.g. 30-day train / 7-day test
2. On each train window: grid-search best parameters
3. Apply those parameters (fixed) to the next test window — measure out-of-sample performance
4. Roll forward 7 days, repeat

The reported result is the **average out-of-sample performance** — much more honest than single-window optimization.

```
|--------- train 1 --------|-test 1-|
         |--------- train 2 --------|-test 2-|
                  |--------- train 3 --------|-test 3-|
```

### Optimization Objective

Default: maximize **Calmar ratio** (annualized return / max drawdown)
Alternative objectives (configurable):
- `profitFactor` — gross profit / gross loss
- `sharpe` — risk-adjusted return
- `expectancy` — expected profit per trade
- `totalPnl` — absolute profit (biased toward high-volume strategies)

Using Calmar by default prevents the optimizer from selecting parameters that produce great returns but catastrophic drawdowns.

## REST Endpoints

```
POST /api/backtest/optimize
{
  "strategy": "EMA_CROSSOVER",
  "pair": "BTC-EUR",
  "interval": 60,
  "startDate": "2026-02-01T00:00:00",
  "endDate": "2026-04-01T00:00:00",
  "paramRanges": {
    "emaShortPeriod": [5, 7, 9, 12],
    "emaLongPeriod": [15, 21, 26, 34]
  },
  "objective": "calmar",
  "method": "grid"    // "grid" or "walkforward"
}

Response:
{
  "bestParams": {"emaShortPeriod": 7, "emaLongPeriod": 26},
  "bestObjective": 3.42,
  "topN": [
    {"params": {"emaShortPeriod": 7, "emaLongPeriod": 26},
     "stats": {"calmar": 3.42, "sharpe": 1.8, "winRate": 0.58, ...}},
    {"params": {"emaShortPeriod": 9, "emaLongPeriod": 26},
     "stats": {"calmar": 3.10, ...}},
    ...
  ],
  "iterations": 16,
  "elapsedMs": 4523
}

POST /api/backtest/optimize-walkforward
{
  "strategy": "EMA_CROSSOVER",
  "pair": "BTC-EUR",
  "interval": 60,
  "paramRanges": {...},
  "startDate": "2026-02-01T00:00:00",
  "endDate": "2026-04-01T00:00:00",
  "trainDays": 30,
  "testDays": 7,
  "objective": "calmar"
}

Response:
{
  "windows": [
    {"trainRange": "...", "testRange": "...",
     "bestParams": {...}, "testObjective": 2.1},
    ...
  ],
  "avgOutOfSampleObjective": 2.4,
  "paramStability": 0.65   // fraction of windows that picked similar params
}
```

## Parallelization

Grid search is embarrassingly parallel — each parameter combination is an independent backtest:

```java
List<OptimizationResult.Entry> results = paramCombinations.parallelStream()
    .map(params -> {
        BacktestResult bt = backtestService.run(request.withParams(params));
        return new Entry(params, bt.stats());
    })
    .sorted(Comparator.comparing(e -> e.stats().calmar(), reverseOrder()))
    .limit(topN)
    .toList();
```

Use a dedicated `ForkJoinPool` sized to available cores — don't steal from the default common pool that Spring uses.

## Implementation Sketch

```java
@Service
@RequiredArgsConstructor
public class ParameterOptimizer {

    private final BacktestService backtestService;

    public OptimizationResult gridSearch(OptimizeRequest request) {
        List<ParamSet> combinations = expandRanges(request.paramRanges());

        try (ForkJoinPool pool = new ForkJoinPool(
                Runtime.getRuntime().availableProcessors())) {
            return pool.submit(() ->
                combinations.parallelStream()
                    .map(params -> evaluate(request, params))
                    .sorted(by(request.objective()).reversed())
                    .limit(20)
                    .collect(OptimizationResult.collector(request))
            ).get();
        }
    }

    public OptimizationResult walkForward(OptimizeRequest request) {
        List<Window> windows = generateWindows(
            request.startDate(), request.endDate(),
            request.trainDays(), request.testDays()
        );

        List<WindowResult> windowResults = windows.stream()
            .map(window -> {
                ParamSet bestOnTrain = gridSearch(window.train()).best();
                BacktestResult outOfSample = backtestService.run(
                    window.test().withParams(bestOnTrain)
                );
                return new WindowResult(window, bestOnTrain, outOfSample.stats());
            })
            .toList();

        return OptimizationResult.walkForward(request, windowResults);
    }
}
```

## Anti-Overfitting Guardrails

Grid search on limited historical data overfits easily. Protections:

1. **Prefer walk-forward** — the default in the MCP tool should be `method: walkforward`
2. **Stability check** — report `paramStability` (how often the same params won across windows)
3. **Out-of-sample penalty** — rank by out-of-sample objective, not in-sample
4. **Parameter complexity penalty** — prefer simpler parameter sets (e.g. prefer integer periods over fractional) — informational only
5. **Minimum trade count** — reject parameter sets that produced fewer than 20 trades (statistical noise)

## Verification

1. Run grid search with 2 params × 3 values each = 9 combinations
2. Verify all 9 ran, results sorted correctly, no crashes
3. Run walk-forward with 4 windows — verify test windows are disjoint from train windows
4. Intentionally overfit: small dataset, wide param range — verify out-of-sample performance << in-sample (confirms the walk-forward protection is working)
5. Verify parallel execution — `Runtime.getRuntime().availableProcessors()` workers active during optimization
6. Run 100 combinations — should complete in seconds with parallelization

## What Strategies Benefit Most

Strategies with 3+ tunable parameters where the defaults are likely suboptimal:

- **EMA_CROSSOVER** — 5 params (emaShort, emaLong, rsiPeriod, rsiOverbought, rsiOversold)
- **MACD** — 3 params (fastPeriod, slowPeriod, signalPeriod)
- **BOLLINGER** — 2 params (period, stdDev multiplier)
- **STOCH_RSI** — 2 params (rsiPeriod, stochPeriod)
- **TRIPLE_EMA** — 3 params (fast, medium, slow periods)

Lower priority:
- **MFI**, **RSI_MOMENTUM**, **CCI** — 1 param (period) — tiny search space
- **ICHIMOKU** — traditionally fixed periods (9/26/52) by convention

## Follow-ups

- Advanced optimization: Bayesian optimization (scikit-optimize port) for continuous parameters
- Genetic algorithms for non-convex objectives
- Regime detection — use different parameters in trending vs ranging markets
