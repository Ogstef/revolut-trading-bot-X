# Future Plans — MCP Servers & Technical Analysis Enhancements

This directory contains forward-looking implementation plans for extending the revolut-trading-bot-X with MCP servers and advanced technical analysis capabilities.

## Current State (as of Phase 11)

The bot is running **72 isolated virtual portfolios** — 12 strategies × 3 pairs × 2 intervals — on a 30-second heartbeat in PAPER mode. The execution unit is `(pair, strategy, interval)`.

| Capability | Status |
|------------|--------|
| 12 strategies (EMA, MACD, Bollinger, RSI, StochRSI, TripleEMA, ParabolicSAR, ADX_DI, CCI, MFI, Donchian, Ichimoku) | ✓ Shipped |
| Multi-pair: BTC-EUR, ETH-EUR, SOL-EUR | ✓ Shipped |
| Multi-interval: 15m + 1h (Phase 11) | ✓ Shipped |
| Per-`(pair, interval, strategy)` circuit breakers | ✓ Shipped |
| `FearGreedService` fetched & exposed on dashboard | ✓ Shipped |
| `FearGreedService` integrated into signal confidence | ✗ Still unused |
| Order book data used for trading decisions | ✗ Still unused |
| Backtesting infrastructure | ✗ Not implemented |
| Parameter optimization | ✗ Not implemented |
| Risk analytics (Sharpe, max drawdown, VaR) | ✗ Not implemented |
| MCP servers configured | ✗ None |
| News/social sentiment | ✗ None |
| On-chain metrics | ✗ None |

## Two MCP Use Cases

| Type | Purpose | Consumer | Example |
|------|---------|----------|---------|
| **Development-time MCP** | Tools Claude Code uses during coding | Claude Code | PostgreSQL MCP querying trade performance |
| **Runtime integrations** | Data sources the bot consumes live | Spring Boot services | CoinGecko BTC dominance |

> Runtime integrations are NOT MCP servers — they are standard Spring `@Service` beans following the `FearGreedService` pattern. The MCP investigation identified WHAT data to fetch; implementation uses Spring conventions.

## Directory Index

### Phase A — Foundation [P0]

| # | Document | Type | Effort |
|---|----------|------|--------|
| A1 | [`01-postgres-mcp.md`](./01-postgres-mcp.md) | Dev-time MCP | 30 min |
| A2 | [`02-market-context-service.md`](./02-market-context-service.md) | Runtime | 1.5 days |
| A3 | [`03-backtest-engine.md`](./03-backtest-engine.md) | Runtime | 3–4 days |

### Phase B — Confirmation & Macro [P1]

| # | Document | Type | Effort |
|---|----------|------|--------|
| B1 | [`04-cross-interval-confirmation.md`](./04-cross-interval-confirmation.md) | Runtime | 1 day |
| B2 | [`05-risk-analytics.md`](./05-risk-analytics.md) | Runtime | 1 day |
| B3 | [`06-coingecko-global-metrics.md`](./06-coingecko-global-metrics.md) | Runtime | 1 day |

### Phase C — Optimization [P1]

| # | Document | Type | Effort |
|---|----------|------|--------|
| C1 | [`07-parameter-optimizer.md`](./07-parameter-optimizer.md) | Runtime | 2 days |
| C2 | [`08-correlation-service.md`](./08-correlation-service.md) | Runtime | 1 day |
| C3 | [`09-custom-backtest-mcp.md`](./09-custom-backtest-mcp.md) | Dev-time MCP | 1 day |

### Phase D — Sentiment & On-Chain [P2]

| # | Document | Type | Effort |
|---|----------|------|--------|
| D1 | [`10-news-sentiment.md`](./10-news-sentiment.md) | Runtime | 1 day |
| D2 | [`11-onchain-metrics.md`](./11-onchain-metrics.md) | Runtime | 2 days |

### Reference

| Document | Purpose |
|----------|---------|
| [`architecture-diagram.md`](./architecture-diagram.md) | System integration overview |
| [`roadmap.md`](./roadmap.md) | Phased rollout with dependencies |

## Dependency Graph

```
A1 (PostgreSQL MCP)     ─── standalone
A2 (MarketContextService) ── standalone
A3 (BacktestService)    ─── standalone
    │
    ├── B1 (Cross-interval confirmation) depends on A2
    ├── B2 (RiskAnalyticsService) depends on A3
    ├── B3 (CoinGecko) depends on A2
    ├── C1 (ParameterOptimizer) depends on A3
    ├── C2 (CorrelationService) depends on A3
    ├── C3 (Custom MCP wrapper) depends on A3 + B2 + C1 + C2
    ├── D1 (News sentiment) depends on A2
    └── D2 (On-chain metrics) depends on A2
```

## Guiding Principles

1. **Reuse existing patterns** — every new service follows the `FearGreedService` template (RestClient + volatile cache + scheduled refresh)
2. **Additive changes only** — no refactors of working 72-portfolio infrastructure
3. **Interval-aware by default** — Phase 11 introduced the `(pair, strategy, interval)` unit; all new code respects it
4. **PAPER first, LIVE later** — backtest → optimize → validate in paper → consider LIVE
5. **Zero new API calls when existing data works** — F&G, order book, and cached BarSeries are all free wins
