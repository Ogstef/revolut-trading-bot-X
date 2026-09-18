# Architecture Integration Map

How all the future plans fit together.

## System-Level View

```
                    DEVELOPMENT-TIME MCP
                    ┌─────────────────────────────────┐
                    │ PostgreSQL MCP → SQL queries    │
                    │ Custom Backtest MCP → REST API  │
                    └─────────────────────────────────┘
                                  │
                          Claude Code sessions
                                  │
                                  ▼
                            ┌───────────┐
                            │ REST API  │
                            └───────────┘
                                  │
┌─────────────────────────────────┴──────────────────────────────┐
│                  RUNTIME (Spring Boot)                         │
│                                                                │
│  Existing (verified):       New Services:                      │
│  ┌──────────────┐           ┌──────────────────┐               │
│  │ Revolut X API│           │ CoinGeckoService │               │
│  │ candles, tkr,│           │ NewsSentimentSvc │               │
│  │ order book   │           │ OnChainMetricsSvc│               │
│  └──────┬───────┘           └────────┬─────────┘               │
│         │                            │                         │
│  ┌──────┴───────┐                    │                         │
│  │FearGreedSvc  │ (UNUSED today)     │                         │
│  └──────┬───────┘                    │                         │
│         │                            │                         │
│         ▼                            ▼                         │
│  ┌───────────────────────────────────────────────┐             │
│  │       MarketContextService (NEW)              │             │
│  │ Inputs: F&G + OrderBook + HTF cache + macros  │             │
│  │ Output: per-(pair, interval) multiplier (.5–1.5)│           │
│  └───────────────────────┬───────────────────────┘             │
│                          │                                     │
│                          ▼                                     │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │  SignalEngine.evaluateAllPairsAndPersist() — TRIPLE LOOP  │ │
│  │  for pair → for interval → for strategy:                  │ │
│  │    signal = strategy.evaluate(...).withInterval(intvl)    │ │
│  │    NEW: ctx = marketContextService.getContext(pair, intvl)│ │
│  │    NEW: signal = signal.withAdjustedConfidence(            │ │
│  │              signal.confidence() * ctx.multiplier())       │ │
│  │    persist(signal)                                         │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │  Backtest & Analytics package (NEW)                       │ │
│  │  BacktestService — interval-aware, uses Phase 11 schema  │ │
│  │  BacktestController — REST API consumed by Custom MCP    │ │
│  │  RiskAnalyticsService — Sharpe, drawdown, VaR, Calmar   │ │
│  │  ParameterOptimizer — grid search + walk-forward         │ │
│  │  CorrelationService — 72×72 portfolio correlations       │ │
│  └───────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────┘
```

## Data Flow — Runtime (Per Trading Cycle, Every 30s)

```
   Revolut X API
        │
        ▼
   MarketDataService  ←── fetches candles per (pair, interval)
        │                  fetches order book per pair
        │                  holds barSeriesMap cache
        ▼
   ┌──────────────────────────────────────────────────┐
   │ MarketContextService.getContext(pair, interval)  │
   │                                                   │
   │  ┌─────────────────┐  ┌──────────────────────┐  │
   │  │ FearGreedService│  │ OrderBook imbalance  │  │
   │  │ (hourly cache)  │  │ (from Revolut API)   │  │
   │  └─────────────────┘  └──────────────────────┘  │
   │                                                   │
   │  ┌──────────────────┐  ┌────────────────────┐   │
   │  │ HTF trend from   │  │ CoinGeckoService   │   │
   │  │ cached BarSeries │  │ (BTC dominance)    │   │
   │  └──────────────────┘  └────────────────────┘   │
   │                                                   │
   │  ┌──────────────────┐  ┌────────────────────┐   │
   │  │ NewsSentimentSvc │  │ OnChainMetricsSvc  │   │
   │  │ (CryptoPanic)    │  │ (Blockchain.com)   │   │
   │  └──────────────────┘  └────────────────────┘   │
   │                                                   │
   │        │ Aggregate all signals                   │
   │        ▼                                          │
   │  Final multiplier (0.5 – 1.5)                    │
   └──────────────────────┬───────────────────────────┘
                          │
                          ▼
        ┌─────────────────────────────────────┐
        │  SignalEngine triple loop           │
        │  For each (pair × interval):        │
        │    For each strategy:               │
        │      signal = strategy.evaluate(..) │
        │      signal.confidence *= multiplier│
        │      persist(signal)                │
        └────────────┬────────────────────────┘
                     │
                     ▼
          TradingLoop (groups by pair+interval)
                     │
                     ▼
          RiskManager (validate)
                     │
                     ▼
          PaperTradingService / LiveTradingService
                     │
                     ▼
          Database: positions, trades, signal_logs
```

## Data Flow — Development-Time (Claude Code Session)

```
 Developer asks: "Which strategy is best for ETH-EUR 1h?"
                     │
                     ▼
            Claude Code picks up tools
                     │
        ┌────────────┴───────────────┐
        │                            │
        ▼                            ▼
 PostgreSQL MCP              Custom Backtest MCP
     │                            │
     ▼                            ▼
SELECT ... FROM             POST /api/backtest/
 trading.trades             compare-strategies
     │                            │
     ▼                            ▼
PostgreSQL DB           BacktestController
                              │
                              ▼
                      BacktestService (runs 12 backtests)
                              │
                              ▼
                      RiskAnalyticsService (computes metrics)
                              │
                              ▼
                      Ranked results returned to Claude Code
                              │
                              ▼
                      "ICHIMOKU leads with 2.1 Sharpe..."
```

## Dependency Graph (What Must Be Built Before What)

```
A1 (PostgreSQL MCP)     ─── standalone, can do immediately
A2 (MarketContextService) ── standalone, integrates F&G + OrderBook
A3 (BacktestService)    ─── standalone, uses Phase 11 schema

Phase B layered on A2 and A3:
├── B1 (Cross-interval confirmation) depends on A2
├── B2 (RiskAnalyticsService) depends on A3
└── B3 (CoinGecko service) depends on A2

Phase C layered on A3 and B2:
├── C1 (ParameterOptimizer) depends on A3
├── C2 (CorrelationService) depends on A3
└── C3 (Custom MCP wrapper) depends on A3 + B2 + C1 + C2

Phase D layered on A2:
├── D1 (News sentiment) depends on A2
└── D2 (On-chain metrics) depends on A2
```

## Confidence Multiplier Composition

Final multiplier is the clamped product of all component multipliers:

```
  raw   = fg_multiplier
        × orderbook_multiplier
        × htf_multiplier
        × coingecko_multiplier
        × news_multiplier
        × onchain_multiplier

  final = clamp(raw, 0.5, 1.5)
```

This composition means:
- Signals that agree with all context sources → boost near 1.5 (strongest signals)
- Signals that conflict with all context sources → damped to 0.5 (weakest signals)
- Context sources can offset each other — one bearish signal can cancel one bullish signal

## File Creation Summary

Total new Java/SQL files across all future plans:

| File | Plan |
|------|------|
| `market/MarketContextService.java` | A2 |
| `market/MarketContext.java` | A2 |
| `market/HtfTrend.java` | B1 |
| `market/CoinGeckoService.java` | B3 |
| `market/OnChainMetricsService.java` | D2 |
| `market/OnChainContext.java` | D2 |
| `sentiment/NewsSentimentService.java` | D1 |
| `sentiment/PairSentiment.java` | D1 |
| `model/dto/CryptoGlobalMetrics.java` | B3 |
| `model/dto/CryptoPanicResponse.java` | D1 |
| `model/dto/BlockchainInfoResponse.java` | D2 |
| `backtest/BacktestService.java` | A3 |
| `backtest/BacktestRequest.java` | A3 |
| `backtest/BacktestResult.java` | A3 |
| `backtest/EquityCurvePoint.java` | A3 |
| `backtest/BacktestController.java` | A3 |
| `backtest/RiskAnalyticsService.java` | B2 |
| `backtest/RiskMetrics.java` | B2 |
| `backtest/ParameterOptimizer.java` | C1 |
| `backtest/ParameterRanges.java` | C1 |
| `backtest/OptimizationResult.java` | C1 |
| `backtest/CorrelationService.java` | C2 |
| `backtest/CorrelationMatrix.java` | C2 |
| `backtest/CorrelationInsight.java` | C2 |
| `db/migration/V6__add_adjusted_confidence.sql` | A2 |
| `mcp-servers/backtest/src/index.ts` | C3 |
| `mcp-servers/backtest/src/tools/*.ts` | C3 |

Modifications to existing files:

| File | Plans |
|------|-------|
| `strategy/SignalEngine.java` | A2 (inject MarketContextService) |
| `strategy/Signal.java` | A2 (add `withAdjustedConfidence`) |
| `model/entity/SignalLog.java` | A2 (add `adjusted_confidence` column) |
| `.claude/settings.local.json` | A1, C3 (add MCP server entries) |
| `application.yml` | B3, D1, D2 (config for new services) |
| `pom.xml` | Unchanged — all features use existing dependencies |
