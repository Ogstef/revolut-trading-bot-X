# Implementation Roadmap

Phased rollout with dependencies, effort estimates, and suggested order.

## Phase A — Foundation [P0]

**Goal:** Get developer-side tooling and the highest-leverage runtime improvement in place. No external service dependencies (except Postgres MCP which installs via npm).

| # | Item | Type | Effort | Dependencies |
|---|------|------|--------|--------------|
| A1 | PostgreSQL MCP config | Dev-time MCP | 30 min | None |
| A2 | MarketContextService (F&G + OrderBook) | Runtime | 1.5 days | None |
| A3 | BacktestService + Controller | Runtime | 3–4 days | None |

**Phase A total effort:** ~5 days
**Phase A outcome:** Ability to query DB via Claude, signals adjusted by Fear & Greed / order book, first backtest results

**Order within Phase A:** A1 first (trivial win), A2 second (starts producing better signals while you work on A3), A3 last (takes the longest).

### Phase A success criteria

- [ ] Claude Code can execute `SELECT ... FROM trading.trades GROUP BY ...` directly
- [ ] `signal_logs.adjusted_confidence` column populated for new signals
- [ ] `POST /api/backtest/run` returns sensible results for `(BTC-EUR, EMA_CROSSOVER, 15m)` over last month
- [ ] All existing tests still pass (additive changes only)

---

## Phase B — Confirmation & Macro [P1]

**Goal:** Layer stronger context onto A2, and get foundational risk analytics running on A3.

| # | Item | Type | Effort | Dependencies |
|---|------|------|--------|--------------|
| B1 | Cross-interval confirmation | Runtime | 1 day | A2 |
| B2 | RiskAnalyticsService | Runtime | 1 day | A3 |
| B3 | CoinGecko global metrics | Runtime | 1 day | A2 |

**Phase B total effort:** ~3 days
**Phase B outcome:** Higher-quality signals (HTF-aligned, macro-aware) + first real risk metrics per portfolio

**Order within Phase B:** B1 and B2 can be done in parallel (independent dependencies); B3 can slot anywhere.

### Phase B success criteria

- [ ] 15m signals conflicting with 1h trend have multiplier < 1.0 in logs
- [ ] `GET /api/analytics/risk?pair=BTC-EUR&interval=1h&strategy=ICHIMOKU` returns valid Sharpe, drawdown, VaR
- [ ] BTC dominance and market cap change exposed via `GET /api/market/global-metrics`
- [ ] ETH-EUR / SOL-EUR signals get dampening when BTC dominance is rising

---

## Phase C — Optimization [P1]

**Goal:** Automate parameter search + cross-portfolio analysis. Culminates in the Custom Backtest MCP that exposes everything to Claude Code.

| # | Item | Type | Effort | Dependencies |
|---|------|------|--------|--------------|
| C1 | ParameterOptimizer | Runtime | 2 days | A3 |
| C2 | CorrelationService | Runtime | 1 day | A3 |
| C3 | Custom Backtest MCP wrapper | Dev-time MCP | 1 day | A3, B2, C1, C2 |

**Phase C total effort:** ~4 days
**Phase C outcome:** Grid search + walk-forward optimization available; portfolio correlation insights exposed; Claude Code can drive backtests conversationally

**Order within Phase C:** C1 and C2 can be done in parallel. C3 comes last (depends on A3, B2, C1, C2).

### Phase C success criteria

- [ ] `POST /api/backtest/optimize` runs 100+ parameter combinations in <60s (parallel)
- [ ] Walk-forward test reports out-of-sample objective per window
- [ ] Correlation matrix identifies ≥1 pair of strategies with >0.85 correlation
- [ ] Claude Code can call `compare_strategies` for BTC-EUR 1h and get a ranked response

---

## Phase D — Sentiment & On-Chain [P2]

**Goal:** Add richer context signals from external data sources. These are lower priority because they require external API keys and have lower signal quality than technical indicators.

| # | Item | Type | Effort | Dependencies |
|---|------|------|--------|--------------|
| D1 | News sentiment (CryptoPanic) | Runtime | 1 day | A2 |
| D2 | On-chain metrics | Runtime | 2 days | A2 |

**Phase D total effort:** ~3 days
**Phase D outcome:** News events and on-chain flows contribute to multiplier; bot avoids trading into major negative news

### Phase D success criteria

- [ ] News sentiment fetched and cached; dashboard shows recent bullish/bearish posts
- [ ] On-chain BTC exchange flow contributes to multiplier for BTC-EUR
- [ ] Multiplier composition log shows all 6 context inputs contributing

---

## Grand Total

**Full plan:** ~15 developer days (or ~3 weeks calendar time at sustainable pace)

## Suggested Execution Order

If you want to deliver value fastest:

1. **Day 1 morning:** A1 (PostgreSQL MCP) — instant productivity boost for every subsequent session
2. **Days 1–3:** A2 (MarketContextService) — starts improving signal quality within the existing 72 portfolios
3. **Days 3–7:** A3 (BacktestService) — the foundation for everything in Phase B+C
4. **Days 7–10:** Pick any two of B1/B2/B3 — each adds distinct value independently
5. **Days 10–14:** C1, C2 in parallel
6. **Days 14–15:** C3 (Custom MCP) — the developer experience payoff
7. **Later:** D1, D2 when external API keys are available and Phase C has been used enough to want more context signals

## Critical Path

The longest chain of dependencies:

```
A3 (BacktestService, 3-4 days)
 → C1 (ParameterOptimizer, 2 days)
    → C3 (Custom MCP, 1 day)
```

So minimum calendar time to reach the end state is ~6-7 days of focused work on the critical path, with other items done in parallel.

## Ship Independently

Every item in every phase can be shipped independently — each provides measurable value without requiring later phases. There is no big-bang release.

## Deferred / Not in Scope

Things this plan deliberately does NOT cover:

- **Live trading service** — already planned as a separate "FINAL Phase" per CLAUDE.md. Go LIVE only after backtests confirm expectancy and risk analytics show acceptable drawdown
- **UI changes** — the React frontend can evolve independently. New REST endpoints are additive; existing UI keeps working
- **Strategy refactoring** — the 12 strategies stay as-is. No rewrites.
- **Multi-regime detection** — a potential Phase E (different parameters in trending vs ranging markets) but not needed now
- **Order flow / tape reading** — requires tick-level data, which Revolut X API doesn't provide
- **Futures / derivatives** — bot is spot-only for now
