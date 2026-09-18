# Backtesting & Strategy Tuning Log

> **Read this first if a fresh chat is asked to "tune the strategies", "evaluate backtest results", "decide what to deploy live", or "look at the backtester."**
>
> Companion docs (don't re-read them unless needed): [`CLAUDE.md`](./CLAUDE.md) for project overview, [`API_CONTRACT.md`](./API_CONTRACT.md) for endpoint shapes, [`revolut-trading-bot/CLAUDE.md`](./revolut-trading-bot/CLAUDE.md) for backend internals.

This document captures **everything we have tested, learned, and decided** about the bot's strategies as of 2026-04-29. It exists so future chats don't re-discover findings or re-suggest experiments that already failed.

---

## 1. Backtester (shipped)

### Architecture (one paragraph)

The backtester replays stored historical candles from `trading.candlesticks` through the *live* strategy code (`TradingStrategy.evaluate`) bar-by-bar. It applies the same cost model as `PaperTradingService` (fee + slippage), simulates TP/SL/SIGNAL_EXIT, and persists every run to `trading.backtest_runs` (V13 migration). Identical inputs produce identical outputs (deterministic). Walk-forward mode splits a date range into N consecutive sub-windows and produces a verdict. Lives in `revolut-trading-bot/src/main/java/com/stefo/revolut_trading_bot/backtest/`.

### Endpoints

| Method | Path | Body | Notes |
|---|---|---|---|
| `POST` | `/api/backtest/run` | `BacktestRequest` | Single run; returns full `BacktestRunDetail` with trades + equity curve. |
| `POST` | `/api/backtest/walk-forward` | `{ "request": BacktestRequest, "windows": int (default 3) }` | Splits date range, returns per-window summaries + variance metrics + verdict. Skipped windows surface in `skippedWindows`. |
| `GET` | `/api/backtest/runs?pair=&strategy=&interval=&limit=` | — | List summaries (no trades/equity in payload). |
| `GET` | `/api/backtest/runs/{id}` | — | Full detail. |
| `DELETE` | `/api/backtest/runs/{id}` | — | Remove. |
| `PATCH` | `/api/backtest/runs/{id}` | `{ label?, notes? }` | Edit metadata. |

Field-level shapes are in `API_CONTRACT.md` §4.10 and §5 (`BacktestRequest`, `BacktestRunDetail`, `BacktestStats`, `SimulatedTrade`, `EquityPoint`, `WalkForwardResult`).

### How to query results

**Direct SQL via the existing `mcp__postgres-vps__query` MCP** (the VPS one occasionally errors with `MCP error -32603`; `mcp__postgres-local__query` is a usable fallback if local DB has been kept in sync, but the VPS DB is the source of truth):

```sql
-- Top runs by net P&L for a strategy
SELECT id, pair, interval, label,
       (stats->>'totalTrades')::int   AS n,
       (stats->>'winRate')::numeric   AS wr,
       (stats->>'netPnl')::numeric    AS net,
       (stats->>'sharpeRatio')::numeric AS sharpe,
       (stats->>'tStatistic')::numeric AS t,
       (stats->>'maxDrawdownPct')::numeric AS dd_pct
  FROM trading.backtest_runs
 WHERE strategy = 'RSI_MOMENTUM'
 ORDER BY (stats->>'netPnl')::numeric DESC
 LIMIT 20;

-- Walk-forward consistency: only show triples where every window was positive
WITH wf AS (
  SELECT pair, strategy, interval,
         regexp_replace(label, ' \\d+/\\d+ — .*', '') AS group_label,
         (stats->>'netPnl')::numeric AS net
    FROM trading.backtest_runs
   WHERE label LIKE 'walk-forward%'
)
SELECT pair, strategy, interval, group_label,
       count(*) AS windows, min(net) AS worst, max(net) AS best, sum(net) AS total
  FROM wf
 GROUP BY 1,2,3,4
 HAVING min(net) > 0
 ORDER BY total DESC;
```

**Direct REST** (also fine, sometimes easier):

```bash
curl -s 'http://204.168.228.158:8080/api/backtest/runs?strategy=RSI_MOMENTUM&limit=20' | jq .
curl -s 'http://204.168.228.158:8080/api/backtest/runs/<uuid>' | jq '.stats'
```

### Tunable parameters (current state)

In `BacktestRequest.paramOverrides` (a `Map<String, Object>`), the simulator recognizes these keys:

| Override key | Affects | Notes |
|---|---|---|
| `takeProfitPct` | All strategies (simulator-side) | Default = `trading.risk.take-profit-pct` (12%) |
| `stopLossPct` | All strategies | Default = 4% |
| `feeRate` | All strategies | Default = 0.0009 |
| `slippageRate` | All strategies | Default = 0.0005 |
| `maxPositionPct` | All strategies | Default = 2% |
| `emaShortPeriod`, `emaLongPeriod`, `rsiPeriod`, `rsiOverbought`, `rsiOversold` | **Only `EMA_CROSSOVER`** | Other strategies have these as `static final int` constants — see `RsiMomentumStrategy.java:37-39`, `BollingerBandsStrategy.java`, `MacdStrategy.java`, `IchimokuStrategy.java`. To tune them you must refactor those classes to read from `TradingConfig` first. |

**Limitation worth noting**: the simulator allows **1 concurrent position per triple** (vs live's `maxConcurrentPositions=3`). This was *the* calibration mismatch but is now mostly mitigated by the bar-cooldown fix below.

### Bar cooldown fix (deployed 2026-04-28, commit `28cc117`)

Live polling runs every 30s; strategies evaluate against the latest closed bar; same bar = same indicator values = same signal. Pre-fix, persistent BUY signals opened up to 3 positions per bar at near-identical prices. The fix gates new entries until the next bar boundary closes (`RiskManager.validateForStrategy` → rule 0 — bar cooldown).

After this fix, **live and backtester both produce one entry per bar**. They should now produce comparable trade counts (within ±2) and similar P&L (within ±10%) over identical windows. Pre-fix calibration showed live had 4 trades vs backtest 3 over Apr 12-22 with ~21× P&L gap; that gap should narrow as fresh post-cooldown data accumulates.

---

## 2. Current bot configuration & disabled triples

**Mode:** PAPER (no live order routing exists).
**Pairs:** BTC-EUR, ETH-EUR, SOL-EUR
**Intervals:** 15m, 1h, 4h, 1d, 1w
**Strategies:** 16 (13 TA + 3 sentiment) — see `revolut-trading-bot/CLAUDE.md`.
**Total triples:** 240 (16 × 3 × 5).

**16 triples are currently disabled** (in `trading.disabled_triples`, gated by `TripleConfigService` → `TradingLoop.runSpotExecution`). 15 from the data-driven cut list, 1 user-added:

| # | Pair | Strategy | Interval | Source |
|---|---|---|---|---|
| 1 | BTC-EUR | TRIPLE_EMA | 15m | cut list (worst bleeder, −€42) |
| 2 | SOL-EUR | TRIPLE_EMA | 15m | cut list |
| 3 | SOL-EUR | TRIPLE_EMA | 1h | cut list |
| 4 | ETH-EUR | TRIPLE_EMA | 15m | cut list |
| 5 | ETH-EUR | TRIPLE_EMA | 1h | cut list |
| 6 | ETH-EUR | SUPERTREND | 15m | cut list |
| 7 | ETH-EUR | TRIPLE_EMA | 4h | cut list |
| 8 | BTC-EUR | DONCHIAN | 15m | cut list |
| 9 | ETH-EUR | EMA_CROSSOVER | 15m | cut list |
| 10 | BTC-EUR | PARABOLIC_SAR | 1h | cut list |
| 11 | SOL-EUR | MACD | 15m | cut list |
| 12 | BTC-EUR | EMA_CROSSOVER | 15m | cut list |
| 13 | SOL-EUR | STOCH_RSI | 1h | cut list |
| 14 | SOL-EUR | SUPERTREND | 15m | cut list |
| 15 | SOL-EUR | EMA_CROSSOVER | 15m | cut list |
| 16 | ETH-EUR | SUPERTREND | 4h | user-added (not on data-driven cut list — leave it) |

Cumulative live bleed removed: ~**€253**. Soft-disable semantics: existing positions still get TP/SL coverage, only NEW entries blocked.

`224 of 240 triples remain active` and continue paper-trading.

---

## 3. Findings (validated insights — do not re-discover)

### 3.1 The dominant pattern across the bot: **fee drag**

Round-trip cost = `feeRate (0.0009) + slippageRate (0.0005) = 0.0014 per side × 2 sides = 0.28%`. On the default `maxPositionPct=2` of €10 000 = €200 position, that's €0.56 per closed trade.

Most strategies have **gross-PnL-per-trade in the €0.05-0.15 range** — well below the €0.56 cost threshold. They lose net even when they win gross. Concrete examples from backtests:

| Triple | Trades | Gross | **Net** | Fees+Slip |
|---|---|---|---|---|
| BTC/CCI/15m W3 | 81 | +€5.46 | **−€39.80** | €45 |
| BTC/BOLLINGER/15m W3 | 55 | +€19.53 | **−€11.26** | €30 |
| BTC/STOCH_RSI/1h (3 windows) | 121 | −€1 | **−€69** | €69 |
| BTC/RSI_MOMENTUM/1h (6mo) | 30 | −€11 | **−€28** | €17 |

**This is the headline finding.** Most TA strategies have direction-picking ability (60%+ win rates) but the per-trade edge is too small for Revolut's cost structure. Tuning TP/SL doesn't fix this; the strategy needs either (a) larger position sizing, (b) coarser intervals (less trading), or (c) a regime filter that skips low-edge bars.

### 3.2 Strategies tested in detail

#### BTC-EUR / RSI_MOMENTUM / 1h — the most thoroughly tested triple

6-month walk-forward (Nov 9 2025 → Apr 28 2026), 3 windows × 4 parameter sets:

| Config | TP/SL | W1 (Nov-Jan) | W2 (Jan-Mar) | W3 (Mar-Apr) | **Total net** |
|---|---|---|---|---|---|
| Baseline | 12 / 4 | −€4.46 (50%) | −€41.65 (33%) | +€17.98 (63%) | **−€28.13** |
| A: tight both | 6 / 3 | −€7.57 (42%) | −€36.11 (31%) | +€18.25 (63%) | **−€25.43** |
| B: wider SL | 12 / 7 | −€10.90 (57%) | **−€54.12** (44%) | +€20.17 (50%) | **−€44.85** |
| C: tight SL, big TP | 15 / 2.5 | −€6.13 (38%) | −€33.22 (25%) | +€1.45 (44%) | **−€37.90** |

**Verdict: REGIME_DEPENDENT, no parameter set fixes window 2.** Mean-reversion strategy works in range markets (W3 — Mar-Apr was rangey), gets stopped out repeatedly in trending markets (W2 — Jan-Mar trended). The single most striking observation: **0 TP_HITs across all 30 baseline trades** — the 12% take-profit is decorative, every winner exits on RSI=70 SIGNAL_EXIT at ~5-9% gain. The actual realized R:R is closer to 1:1 than the configured 3:1.

**Param tuning has hit a wall on this triple.** Wider SL makes things worse (lets losses grow in trends), tighter SL creates 10-trade losing streaks. Baseline 12/4 is roughly optimal — and roughly optimal here means "loses €28 over 6 months."

#### BTC-EUR / RSI_MOMENTUM / 15m — small-history triple, looks better

Single runs (30-90 day windows; 15m candles only go back ~47 days, walk-forward of 3 windows over Jan 28→Apr 28 silently skipped W1 due to no candles before Mar 11):

| Window | Trades | Win % | Net |
|---|---|---|---|
| Last 90 days | 16 | 68.8% | **+€6.16** ✓ |
| Last 30 days (Mar 28→Apr 28) | 11 | 81.8% | **+€17.68** ✓ |
| Last 30 days (Mar 28→Apr 28, repeat) | 11 | 81.8% | **+€15.25** ✓ |
| Walk-forward W2 only (Feb 27→Mar 29, partial) | 5 | 40% | −€11.24 |
| Walk-forward W3 only (Mar 29→Apr 28) | 12 | 83.3% | +€19.82 ✓ |

This looked promising on observation but **all available history is post-March 11, 2026** so we can't actually walk-forward this triple over a long window. The 1h variant (which DOES have 6 months of history) is REGIME_DEPENDENT, which is suggestive that the 15m variant is also regime-dependent and we just happen to be in a friendly regime currently.

**Decision pending: the ADX regime filter (next plan)** is the right test. Until that lands, don't deploy this triple live just because the recent month looks good.

#### Other triples worth knowing about

- **BTC/CCI/15m**: top live performer (20 trades, +€34, 80% wr) but backtest walk-forward W3 had 81 trades / +€5 gross / **−€40 net** → fee drag killed it. Live's profitability was likely the polling-stack effect (now fixed) plus a friendly recent regime.
- **BTC/BOLLINGER/15m**: 18 live trades, 83% wr, +€20 net live; but backtest W3 had 55 trades / +€19 gross / −€11 net. Same fee drag.
- **BTC/EMA_CROSSOVER/1h**: 1 of 3 walk-forward windows positive (+€10 in W2 Feb-Mar), other 2 lost. Strong REGIME_DEPENDENT signature — candidate for an MA200 trend filter.
- **BTC/STOCH_RSI/1h**: 121 trades over 3 walk-forward windows, **all 3 windows lose net** (−€17, −€26, −€26) despite 57-68% win rates. Pure fee-drag death. Don't waste more time.
- **BTC/TRIPLE_EMA/15m**: 63 trades, −€40 net, 27% win rate. Confirmed cut-list correct.

### 3.3 Live vs. backtest divergence (resolved)

Pre-cooldown calibration: live BTC/RSI_MOMENTUM/15m had 4 closed trades + 3 still-open in Apr 12-22, +€42 net. Backtest over the same window: 3 trades, +€2 net. The gap was almost entirely the bot's polling-stack bug — Apr 19 had 3 simultaneous BUY entries at €63100 within 1 minute (one signal observed 3 times across 30s polling cycles) that all closed near €67000, accounting for ~€37 of the +€42.

**Post-cooldown** (deployed 2026-04-28), live and backtest both enforce one-entry-per-bar. Calibration should now converge. Expect future live trade counts to drop 30-50% on triples that were stacking; net P&L per closed trade should also become a more honest measurement.

A useful follow-up calibration to run after a week of post-cooldown data: backtest the same window the bot ran live, compare trade count (target ±2) and net P&L (target ±10%).

### 3.4 Things that didn't work — don't try again

- **Wider stop loss to "let trades recover"** (Experiment B, SL=7% on RSI_MOMENTUM/1h). Made W2 dramatically worse (−€54 vs baseline −€42). In trending markets, wider stops just let losses grow.
- **Tightening TP to capture exits earlier** (Experiment A, TP=6%). Did fire 2 TP_HITs (vs 0 baseline) but overall result essentially identical to baseline (−€25 vs −€28).
- **Tight SL + generous TP** (Experiment C, SL=2.5% / TP=15%). Created a 10-trade losing streak in W2. Tight stops in trending markets = death by a thousand cuts.

The pattern: **for a regime-dependent strategy, no R:R tuning fixes the regime mismatch.**

---

## 4. Open questions / next experiments

### 4.1 Highest-priority planned work

**ADX regime filter on RSI_MOMENTUM** (the immediate next plan as of 2026-04-29). Hypothesis: skipping BUY signals when ADX > ~25 (trending market) lets the mean-reversion strategy avoid the W2-style regimes where it gets stopped out repeatedly. Code lives in `RsiMomentumStrategy.evaluate` — would compute ADX(14) on the same `BarSeries`, return HOLD with reason "regime: trending" when above threshold. Half-day implementation.

After that lands, re-run the same 6-month walk-forward (BTC/1h) and compare. Success criteria: W2 net moves from −€42 toward break-even AND W3 stays positive (filter doesn't kill the good regime).

### 4.2 Other unfinished experiments worth running someday

- **Per-strategy param refactor**: Bollinger period (20), MACD (12/26/9), Ichimoku (9/26/52) etc. are hardcoded `static final int`. If we unblock parameter optimization across strategies, these need to be pulled into `TradingConfig`. Listed in `docs/future-plans/07-parameter-optimizer.md` and is a prerequisite for the param optimizer.
- **MA200 trend filter on EMA_CROSSOVER and TRIPLE_EMA**. The trend strategies are currently disabled on 15m. With an MA200 filter (only fire when above MA200 for longs, etc.), they might survive on higher intervals.
- **Larger position sizing experiment**: bump `maxPositionPct` from 2 to 4 or 5. Doesn't change edge but doubles/triples gross PnL while fees stay roughly the same percentage of notional → fee drag ratio improves. Risk: bigger drawdowns. Worth testing in backtest only.
- **Coarser intervals**: most strategies tested on 15m and 1h. The 4h and 1d have far fewer trades (less fee drag) but also less data. Worth a quick sweep for the strategies that show direction-picking ability on lower timeframes.

### 4.3 Things to NOT do without explicit user approval

- **Don't mass-disable triples by guessing.** The current 16-triple cut list is data-driven from the live trades table; future cuts should be similarly grounded.
- **Don't promote any triple to LIVE-mode trading.** The bot has no live order routing path (mode=PAPER). Promoting requires a separate plan covering Revolut Ed25519 signing for orders, position reconciliation, etc.
- **Don't refactor strategy classes without first backtesting the proposed change.** The default Bollinger 20/2, MACD 12/26/9, Ichimoku 9/26/52 are industry conventions for a reason; pulling them into config is unblocking work, not improvement work.

---

## 5. Reference

### Key file paths

| Concern | Path |
|---|---|
| Backtester engine | `revolut-trading-bot/src/main/java/com/stefo/revolut_trading_bot/backtest/BacktestService.java` |
| Stats math | `…/backtest/BacktestStatsCalculator.java` |
| Endpoints | `…/backtest/BacktestController.java` |
| Persisted runs | `trading.backtest_runs` (JSONB columns: `params`, `stats`, `trades`, `equity_curve`) |
| V13 migration | `revolut-trading-bot/src/main/resources/db/migration/V13__add_backtest_runs.sql` |
| Live cost model | `…/execution/PaperTradingService.java:57-127` (mirror this exactly in any sim changes) |
| TP/SL exit logic | `…/risk/TakeProfitStopLossManager.java:55` |
| Bar cooldown rule | `…/risk/RiskManager.java` (rule 0 in `validateForStrategy`) |
| Triple disable | `…/service/TripleConfigService.java`, table `trading.disabled_triples` (V12) |
| UI page | `revolut-trading-bot-ui/src/pages/BacktestPage.tsx` |
| UI hook | `revolut-trading-bot-ui/src/hooks/useBacktest.ts` |

### How to deploy after a strategy change

The bot uses `deployed-version` branches on both repos. After committing to `develop` (outer) or `deployed-version` (UI):

```bash
# Backend
ssh -A stef@204.168.228.158 'set -e; cd /opt/revolut-deploy/backend && git pull --ff-only && cd revolut-trading-bot && mvn clean package -Dmaven.test.skip=true -q && cp target/revolut-trading-bot-0.0.1-SNAPSHOT.jar /opt/revolut-trading-bot/app.jar && sudo systemctl restart revolut-trading-bot && sleep 5 && sudo systemctl is-active revolut-trading-bot'

# UI
ssh -A stef@204.168.228.158 'set -e; cd /opt/revolut-deploy/ui && git pull --ff-only && npm ci && npx vite build && rsync -a --delete dist/ /var/www/revolut-trading-bot-ui/'
```

(Prefer the `deploy-backend` / `deploy-ui` / `deploy-all` skills if available in the chat session — same commands, packaged.)

### Health-check after deploy

```bash
curl -s http://204.168.228.158:8080/actuator/health
curl -s http://204.168.228.158:8080/api/status
curl -s http://204.168.228.158:8080/ | grep -o 'assets/index-[^"]*\.js'   # UI bundle hash
```

---

## 6. Document maintenance

When you (a future Claude) finish a meaningful experiment, **update this file**:

- New backtest finding → add a row to §3.2 (per-triple results) or extend §3.3 if it's a calibration/methodology insight.
- New experiment failed in a way that should not be retried → add to §3.4.
- Param/strategy class became newly tunable → update §1 "Tunable parameters".
- Triples got enabled/disabled → update §2.
- New planned experiment → §4.1 or §4.2.

Don't grow it indefinitely; if a finding gets superseded by a later one, replace rather than append. The file's value is being scannable in <5 minutes by a fresh chat.
