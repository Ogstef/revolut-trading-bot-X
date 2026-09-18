# Future Plans

Everything here is unbuilt. The system is currently running 195 paper portfolios (13 strategies × 3 pairs × 5 intervals) with fee/slippage modelling and Telegram notifications. These are the next steps.

---

## 1 — Live Trading

**Prerequisites before enabling LIVE mode:**
- At least one strategy shows consistent positive net expectancy (> €0 per trade after fees/slippage) over several weeks
- Win rate stable across enough sample trades (not luck — check sample size)
- You understand the worst drawdown and are comfortable with it in real money

**What needs to be built:**
- `LiveTradingService` — real Revolut X order placement via `POST /orders`
  - Market order on BUY: `{ "order_configuration": { "market": { "quote_size": "100.00" } } }`
  - Market order on SELL: closes position at market price
  - Stores `venue_order_id` on `Position` for order tracking
- `GET /orders/{venue_order_id}` polling — confirm fill before recording position as OPEN
- TP/SL as real Revolut limit orders (so positions close even if the app is offline)
- `trading.mode: LIVE` switch in `application.yml` — `OrderExecutionService` routes to `LiveTradingService`
- Start with minimum position sizes (€10–25 per trade)
- Paper and Live can run on different pairs simultaneously during transition

**Safety rules that must hold in LIVE mode:**
- NEVER place a real order without logging the full request first
- NEVER bypass the risk manager — `validate()` must return approved before any order
- Fee/slippage modelling: in LIVE mode, use actual fill prices from the Revolut order-fill response rather than the flat-rate model
- Circuit breakers apply to LIVE positions — no exceptions

---

## 2 — Backtest Engine

**Goal:** Replay historical candles through existing strategy logic to measure past performance before allocating real capital.

**Design:**
- `BacktestService` — takes `(pair, interval, strategy, from, to)`, fetches historical candles from `candlesticks` table (or Revolut API), runs strategy evaluation, simulates position open/close with fee model, returns P&L curve
- `POST /api/backtest/run` endpoint
- Walk-forward validation: split history into in-sample (optimise) and out-of-sample (validate)
- Results stored in a `backtest_runs` table so they can be compared

**Why:** Paper trading only tells you future performance. Backtests let you see how a strategy would have behaved on past data — useful for tuning TP/SL percentages, confirming a strategy isn't purely curve-fit.

---

## 3 — Parameter Optimiser

**Goal:** Automated grid search over `(takeProfitPct, stopLossPct, rsiOverbought, rsiOversold, emaShortPeriod, emaLongPeriod)` to find the parameter set that maximises net expectancy on a given backtest window.

**Depends on:** Backtest Engine.

**Design:**
- `ParameterOptimizer` — runs N parameter combinations in parallel via `CompletableFuture`
- `POST /api/backtest/optimize` endpoint
- Walk-forward mode: optimise on rolling in-sample windows, validate on subsequent out-of-sample window
- Output: ranked parameter sets by out-of-sample expectancy

---

## 4 — Cross-Interval Confirmation

**Goal:** Before firing a 15m signal, check whether the 1h (or 4h) trend agrees. Conflicting HTF trend → reduce confidence or skip the trade.

**Design:**
- `MarketContextService` method: `trendAlignment(pair, shortInterval, longInterval)` → multiplier in [0.5, 1.5]
- Inject into `OrderExecutionService` — if multiplier < threshold (e.g. 0.7), treat as HOLD even if strategy says BUY
- Zero changes to strategy implementation files

---

## 5 — Sentiment Dashboard

The `FearGreedService` already exists (1h in-memory cache from `api.alternative.me/fng`). It is exposed via `GET /api/market/fear-greed` but not persisted.

**What's not built yet:**
- Persistence of F&G snapshots to a `sentiment_snapshots` DB table
- Reddit chatter intensity scoring (post/comment velocity, z-score normalised to 0–100)
- Blended sentiment endpoint `GET /api/sentiment/current?pair=` combining F&G + Reddit
- History endpoint for charting
- Scheduled refresh jobs

**Scope constraint:** observation-only first pass — no effect on strategy signals, risk manager, or order execution until the data has been trusted for several weeks.

**Not in scope:** Twitter/X (no viable free API), HTML scraping, LLM-based sentiment scoring, keyword-polarity lexicons.

---

## 6 — Risk Analytics

**Goal:** Per-portfolio risk metrics beyond win rate and expectancy.

**Metrics to add:**
- Sharpe ratio (annualised)
- Maximum drawdown (peak-to-trough on the equity curve)
- Value at Risk (95th percentile 1-day loss)
- Calmar ratio (annualised return / max drawdown)

**Endpoint:** `GET /api/analytics/risk?pair=&interval=&strategy=`

**Depends on:** Backtest Engine (for equity curve construction) or sufficient live paper history.

---

## 7 — New Strategies (candidates)

Strategies that cover signal categories not yet represented:

- **Heikin Ashi trend filter** — smoother candles, reduces noise in trending markets
- **Volume-weighted RSI** — like MFI but more sensitive to intraday volume spikes
- **Keltner Channel** — ATR-based channel, different from Bollinger (uses ATR not stddev)
- **Market Regime Detection** — classify market as trending/ranging before applying oscillator vs trend-following strategies

Adding a strategy is purely additive: implement `TradingStrategy`, add to `StrategyType` enum, add balance entries to `application.yml`, and Spring autowires it automatically.

---

## 8 — CoinGecko Global Metrics

**Goal:** BTC dominance and total market cap as macro context signals.

**Design:** `GET https://api.coingecko.com/api/v3/global` — free tier, 30 calls/min. Cache 1h. Expose via `GET /api/market/global-metrics`. Use rising BTC dominance to dampen ETH/SOL signals (capital rotation effect).

---

## 9 — Correlation Analysis

**Goal:** Identify strategies whose signals are highly correlated with each other. If two strategies have >0.85 signal correlation on the same pair, running both just doubles position size without diversification benefit.

**Design:**
- `CorrelationService` — loads recent `signal_logs`, computes pairwise Pearson correlation of `signal_type` over rolling window
- Expose via `GET /api/analytics/correlation?pair=&interval=`
- Use output to decide which strategies to disable or underweight

---

## Execution Order (suggested)

1. **Backtest engine** first — everything else builds on it
2. **Parameter optimiser** once backtest is running
3. **Risk analytics** using the backtest equity curve
4. **Cross-interval confirmation** — quick to add, immediately improves signal quality
5. **Live trading** — only after backtest + risk analytics confirm a strategy is viable
6. **Sentiment dashboard**, **correlation analysis**, **new strategies** — ongoing, any order

The sentiment dashboard and global metrics are lower priority because the signal quality benefit is harder to measure than backtested technicals.
