# API Contract — Revolut Trading Bot

> **Single source of truth** for the HTTP contract between the Spring Boot backend (`revolut-trading-bot/`) and the React/TypeScript UI (`revolut-trading-bot-ui/`). When either side changes an endpoint, DTO, or enum, this document MUST be updated in the same PR.
>
> **Scope**: only the frontend-facing `/api/**` surface. The `/test/**` controller is dev-only and not part of the contract.
>
> **Tier 2 note:** The Consensus and Cross-Interval UI tabs ship without any contract changes — both aggregate existing `/api/signals/current` and `/api/stats/all-triples` responses client-side, plus a 5-way fan-out of `/api/strategies/{name}/trades` for the cross-interval equity chart.

---

## 1. Transport

| Item | Value |
|------|-------|
| Backend base URL (dev) | `http://localhost:8089` |
| Frontend base URL (dev) | `http://localhost:5173` |
| Vite proxy | `/api/*` → `http://localhost:8089` (see `revolut-trading-bot-ui/vite.config.ts`) |
| Content-Type | `application/json` (always) |
| Auth | **None**. All `/api/**` endpoints are public. Production hardening is future work. |
| CORS | Origin `http://localhost:5173`, methods `GET/POST/PUT/DELETE/OPTIONS`, all headers. See `CorsConfig.java`. |
| Date/time | ISO 8601 strings (`JacksonConfig` disables `WRITE_DATES_AS_TIMESTAMPS`) |
| Decimals | Plain JSON numbers (Jackson serializes `BigDecimal` as number) |
| Response wrapping | None — DTOs and entities are returned at the top level |
| Error format | Spring Boot default (`{ timestamp, status, error, path, message }`). No global `@ControllerAdvice` yet. Frontend throws from `request()` on any non-2xx. |

---

## 2. Shared enums

All enums are serialized as their **string name** (Jackson + JPA `@Enumerated(EnumType.STRING)`).

### `StrategyType` (16 values)

| Enum name | Display name |
|-----------|--------------|
| `EMA_CROSSOVER` | EMA Crossover |
| `MACD` | MACD |
| `BOLLINGER` | Bollinger Bands |
| `RSI_MOMENTUM` | RSI Momentum |
| `STOCH_RSI` | Stochastic RSI |
| `TRIPLE_EMA` | Triple EMA |
| `PARABOLIC_SAR` | Parabolic SAR |
| `ADX_DI` | ADX + Directional Index |
| `CCI` | CCI |
| `MFI` | Money Flow Index |
| `DONCHIAN` | Donchian Breakout |
| `ICHIMOKU` | Ichimoku Cloud |
| `SUPERTREND` | Supertrend |
| `REDDIT_SENTIMENT` | Reddit Sentiment |
| `CRYPTOPANIC_SENTIMENT` | CryptoPanic Sentiment |
| `COMBINED_SENTIMENT` | Combined Sentiment |

### `SentimentSource` (3 values)

Used by `GET /api/market/sentiment?source=…`. `COMBINED` is computed in the service from `REDDIT` + `CRYPTOPANIC` — it is never persisted.

| Enum name | Display name |
|-----------|--------------|
| `REDDIT` | Reddit |
| `CRYPTOPANIC` | CryptoPanic |
| `COMBINED` | Combined |

### Other enums

| Enum | Values |
|------|--------|
| `SignalType` | `BUY`, `SELL`, `HOLD` |
| `OrderSide` | `BUY`, `SELL` |
| `OrderStatus` | `OPEN`, `CLOSED` |
| `TradingMode` | `PAPER`, `LIVE` |
| `Trade.exitReason` (free-form string) | `TP_HIT`, `SL_HIT`, `SIGNAL_EXIT`, `MANUAL` |
| `BotEventType` | `POSITION_OPENED`, `POSITION_CLOSED`, `CIRCUIT_BREAKER_TRIPPED`, `CIRCUIT_BREAKER_RESET`, `BOT_STOPPED`, `BOT_RESUMED`, `CONFIG_CHANGED`, `TRIPLE_TOGGLED` |
| `BotEventSeverity` | `INFO`, `WARNING`, `CRITICAL` |

### Format conventions

- **Pair**: hyphenated uppercase — `"BTC-EUR"`, `"ETH-EUR"`, `"SOL-EUR"`. Never `"BTC/EUR"`.
- **Interval**: use the `label` field returned by `GET /api/intervals` (`"15m"`, `"1h"`, etc.). The backend also accepts the raw minutes string but `label` is the canonical form.

---

## 3. Query-parameter conventions

| Param | Type | Required | Default | Notes |
|-------|------|----------|---------|-------|
| `pair` | string | optional | `trading.pairs[0]` (`BTC-EUR`) | Filter by trading pair |
| `interval` | string | optional | `trading.intervals[0]` (`15m`) | Filter by candle interval |
| `limit` | int | optional | endpoint-specific (20 for signals, 50 for trades) | Max rows to return |
| `from` | ISO 8601 `LocalDateTime` | optional | none (no lower bound) | History endpoint only — inclusive lower bound on `executedAt` |
| `to` | ISO 8601 `LocalDateTime` | optional | none (no upper bound) | History endpoint only — inclusive upper bound on `executedAt` |

- No offset/cursor pagination. All list endpoints return **newest-first**.
- Path variables for strategy names use the raw enum name (`EMA_CROSSOVER`, not `ema_crossover`).

---

## 4. Endpoints

### 4.1 Bot status & control

#### `GET /api/status`
- Response: [`BotStatusResponse`](#botstatusresponse)
- UI polling: 10s (`Header`)

#### `POST /api/emergency-stop`
- Request body: none
- Response: `string` (plain text acknowledgment)

#### `POST /api/resume`
- Request body: none
- Response: `string`

#### `POST /api/config`
- Request body: [`ConfigUpdateRequest`](#configupdaterequest) (all fields optional — patch semantics)
- Response: `string`

---

### 4.2 Discovery

#### `GET /api/pairs`
- Response: `PairInfo[]` — see [`PairInfo`](#pairinfo)
- UI: fetched once, cached with `staleTime: Infinity`

#### `GET /api/intervals`
- Response: `IntervalInfo[]` — see [`IntervalInfo`](#intervalinfo)
- UI: fetched once, cached with `staleTime: Infinity`

---

### 4.3 Strategies

All endpoints in this section accept `?pair=&interval=` and scope the response to that `(pair, strategy, interval)` virtual portfolio.

#### `GET /api/strategies`
- Query: `pair?`, `interval?`
- Response: `StrategyInfo[]` — see [`StrategyInfo`](#strategyinfo)
- UI polling: 15s

#### `GET /api/strategies/{strategyType}/positions`
- Path: `strategyType` — `StrategyType` enum name
- Query: `pair?`, `interval?`
- Response: [`PositionView[]`](#positionview)
- UI polling: 15s

#### `GET /api/positions/live`
- Query: none
- Response: [`PositionView[]`](#positionview) — every `OPEN` position across every `(pair, strategy, interval)` triple, each enriched with `interval`, `strategyName`, `displayName`, and live `currentPrice` / `unrealisedPnl`.
- UI polling: 15s (Positions tab)

#### `GET /api/strategies/{strategyType}/trades`
- Path: `strategyType`
- Query: `pair?`, `interval?`, `limit?` (default 50)
- Response: [`Trade[]`](#trade)
- UI polling: 30s (uses `limit=500` on the Overview page for cumulative-PnL charts)

#### `GET /api/strategies/{strategyType}/history`
- Path: `strategyType`
- Query: `pair?`, `interval?`, `from?`, `to?`
- Response: [`TradeHistoryEntry[]`](#tradehistoryentry) — newest first
- No pagination — datasets per `(pair, interval, strategy)` are bounded
- UI polling: 60s (History tab)

#### `GET /api/strategies/{strategyType}/stats`
- Path: `strategyType`
- Query: `pair?`, `interval?`
- Response: [`TradingStats`](#tradingstats)
- UI polling: 60s

#### `GET /api/strategies/{strategyType}/pnl`
- Path: `strategyType`
- Query: `pair?`, `interval?`
- Response: [`PnlBreakdown`](#pnlbreakdown)
- UI polling: 60s

#### `GET /api/strategies/{strategyType}/signals`
- Path: `strategyType`
- Query: `pair?`, `interval?`, `limit?` (default 20)
- Response: [`SignalLog[]`](#signallog)
- UI polling: 30s

---

### 4.4 Signals aggregation

#### `GET /api/signals/summary`
- Query: `pair?`, `interval?`
- Response: `SignalSummaryRow[]` — see [`SignalSummaryRow`](#signalsummaryrow)
- UI polling: 60s

#### `GET /api/signals/current`
- Query: `pair?`, `interval?` (both optional — omit to get every configured triple)
- Response: [`CurrentSignal[]`](#currentsignal)
- Returns the **most recent** signal per `(pair, strategy, interval)` triple. The response always contains one row for every configured triple; fields are `null` when no signal has been evaluated yet. With both params omitted, size = `pairs × intervals × strategies` (currently 3 × 5 × 12 = 180).
- Backed by `SELECT DISTINCT ON (pair, interval, strategy_name)` over `trading.signal_logs`, using index `idx_signal_logs_pair_interval_strategy`.
- UI polling: 30s (Signals tab)

#### `GET /api/stats/all-triples`
- Query: none
- Response: [`TripleStats[]`](#triplestats) — one row per configured `(pair, interval, strategy)` triple. Total rows = `pairs × intervals × strategies` (currently 180). Empty triples (no closed trades) are returned with zeroed numeric fields.
- Backed by one `GROUP BY (pair, interval, strategy_name)` aggregate over `trading.trades`, plus the existing `PositionRepository.countByStatusGroupedByPairIntervalStrategy(OPEN)` rollup, joined client-side in `StatsAggregationService`.
- The `enabled` field on each row is read from `TripleConfigService` — sparse mirror of `trading.disabled_triples`.
- UI polling: 30s (Leaderboard tab)

---

### 4.4½ Triple enable/disable

Per-triple soft-disable. `disable` blocks NEW entries on the next cycle; existing positions keep their TP/SL monitor and exit naturally. State persists in `trading.disabled_triples`.

#### `GET /api/triples/disabled`
- Query: none
- Response: [`DisabledTripleResponse[]`](#disabledtripleresponse) — only currently-disabled triples, newest first.

#### `POST /api/triples/{pair}/{strategy}/{interval}/disable`
- Path: `pair` (e.g. `BTC-EUR`), `strategy` ([`StrategyType`](#strategytype-16-values) enum name), `interval` (e.g. `15m`).
- Body: optional [`TripleDisableRequest`](#tripledisablerequest) — `{ "reason": "..." }` (free-form audit text).
- Response: [`DisabledTripleResponse`](#disabledtripleresponse) for the newly-disabled row.
- 400 if `pair` is not in `trading.pairs` or `interval` is not in `trading.intervals`.
- Idempotent on the cache; the DB row is updated/replaced if the triple was already disabled.

#### `POST /api/triples/{pair}/{strategy}/{interval}/enable`
- Path: same as `/disable`.
- Body: none.
- Response: plain text confirmation.
- 400 on unknown `pair`/`interval`. Idempotent — re-enabling an already-enabled triple is a no-op.

---

### 4.4¾ Today summary

#### `GET /api/today`
- Query: none
- Response: [`TodaySummary`](#todaysummary) — single object aggregating every closed trade since `LocalDate.now().atStartOfDay()` across all `(pair, interval, strategy)` triples.
- Backed by `TradeRepository.findClosedTradesSince(startOfDay)` + an in-memory group-by-triple in `TodaySummaryService`. `openPositions` lists positions opened today that are still `OPEN`, enriched with live `currentPrice` / `unrealisedPnl` via `MarketDataService`.
- UI polling: 30s (Today tab).

---

### 4.5 Activity

#### `GET /api/activity`
- Query: `limit?` (default `100`, hard-capped at 500), `types?` — optional comma-separated list of [`BotEventType`](#botevent) values (e.g. `?types=POSITION_OPENED,CIRCUIT_BREAKER_TRIPPED`)
- Response: [`BotEvent[]`](#botevent) — reverse-chronological audit rows from `trading.bot_events`.
- Events persisted at the point they occur: position opens/closes, circuit-breaker trip/reset transitions, bot stop/resume, config changes.
- UI polling: 15s (Activity tab)

---

### 4.6 Market

#### `GET /api/market/sentiment`
Query params:
- `pair` (default `BTC-EUR`)
- `interval` (optional, defaults to `trading.intervals[0]`)
- `source` (`REDDIT` / `CRYPTOPANIC` / `COMBINED`; default `COMBINED`)

Response: [`SentimentResponse`](#sentimentresponse) — windowed aggregate with sub-scores when `source=COMBINED`. UI polling: 2 min.

#### `GET /api/market/fear-greed`
- Response: [`FearGreedResponse`](#fearreedresponse) — or **`204 No Content`** if the upstream provider is unavailable
- Backend caches the value for 1 hour
- UI polling: 5 min

---

### 4.7 Candles (Charts tab)

#### `GET /api/candles`
- Query: `pair` (required), `interval` (required), `limit?` (default 500, max 1000+)
- Response: [`CandleBar[]`](#candlebar) — last `limit` candles in **ascending** time order
- UI polling: 30s (`useCandles` — Charts tab)
- `time` is **Unix epoch seconds** (not milliseconds) — format expected by lightweight-charts

---

### 4.9 Sentiment ingest (scraper → backend)

Used by the standalone `scrapers/sentiment-scraper/` microservice. Both endpoints share:
- **Auth:** `Authorization: Bearer ${sentiment.ingest.auth-token}`. Missing / mismatched → `401`.
- **Kill switch:** when `sentiment.enabled: false` the filter returns `503` before parsing the body.
- **Batch limits:** bodies with more than `sentiment.ingest.max-batch-size` posts return `413`.
- Response: [`IngestResponse`](#ingestresponse)

#### `POST /api/sentiment/ingest/reddit`
Body: [`RedditIngestRequest`](#redditingestrequest). Server pre-filters (min-score, min-comments, max-age), dedups on `(source=REDDIT, external_id)`, classifies via Claude Haiku (budget-gated, hard cap €3/mo), persists one `sentiment_snapshot` per (post × pairHint).

#### `POST /api/sentiment/ingest/cryptopanic`
Body: [`CryptoPanicIngestRequest`](#cryptopanicingestrequest). Score = `(positive - negative) / max(positive + negative, 1)` ∈ [-1, +1]. Dedups on `(source=CRYPTOPANIC, external_id)`, persists one row per (post × currencyCode→pair).

---

### 4.8 Legacy / global (no interval scoping)

These endpoints predate multi-interval support and are retained for back-compat. New UI code should prefer the per-strategy endpoints in §4.3.

| Endpoint | Query | Response |
|----------|-------|----------|
| `GET /api/positions` | `pair?` | [`PositionView[]`](#positionview) |
| `GET /api/trades` | `limit?` (default 50), `pair?` | [`Trade[]`](#trade) |
| `GET /api/stats` | — | [`TradingStats`](#tradingstats) |
| `GET /api/pnl` | — | [`PnlBreakdown`](#pnlbreakdown) |

---

### 4.10 Backtesting

Replays historical candles from `trading.candlesticks` through the live strategy code, applying the same fee + slippage cost model as the paper trader. Every run is persisted in `trading.backtest_runs` (JSONB columns for `params`, `stats`, `trades`, `equity_curve`).

#### `POST /api/backtest/run`
- Body: [`BacktestRequest`](#backtestrequest)
- Response: [`BacktestRunDetail`](#backtestrundetail) — full result including the trade list and equity curve.
- 400 on unknown pair / interval, end_date ≤ start_date, or fewer than 30 candles in window.
- Synchronous; a 90-day single-triple run completes in well under 1 second.

#### `POST /api/backtest/walk-forward`
- Body: `{ "request": BacktestRequest, "windows": int (default 3, range [2, 10]) }`
- Splits `[startDate, endDate]` into `windows` equal sub-ranges, runs the same config on each, persists each as its own row in `trading.backtest_runs`.
- Response: [`WalkForwardResult`](#walkforwardresult) — per-window summaries + variance metrics + a heuristic `STABLE` / `REGIME_DEPENDENT` / `WILDLY_VARYING` verdict on whether the edge is consistent.

#### `GET /api/backtest/runs`
- Query: `pair?`, `strategy?` ([`StrategyType`](#strategytype-16-values)), `interval?`, `limit?` (default 50, hard-capped at 200)
- Response: [`BacktestRunSummary[]`](#backtestrunsummary) — newest first. No trades or equity curve in the payload.

#### `GET /api/backtest/runs/{id}`
- Path: UUID
- Response: [`BacktestRunDetail`](#backtestrundetail) (same shape as POST `/run`)
- 404 if not found.

#### `DELETE /api/backtest/runs/{id}`
- Removes the run row. 204 on success, 404 if not found (idempotent OK semantics if you prefer).

#### `PATCH /api/backtest/runs/{id}`
- Body: `{ "label"?: string, "notes"?: string }` — only the supplied keys are updated.
- Response: [`BacktestRunSummary`](#backtestrunsummary)

---

## 5. DTO schemas

TypeScript types live in `revolut-trading-bot-ui/src/api/client.ts`. Java DTOs live in `revolut-trading-bot/src/main/java/com/stefo/revolut_trading_bot/model/dto/`.

### `BotStatusResponse`

| Field | JSON type | Nullable | Notes |
|-------|-----------|----------|-------|
| `running` | boolean | no | Trading loop is active |
| `mode` | string | no | `"PAPER"` or `"LIVE"` |
| `pair` | string | no | Primary pair (`trading.pairs[0]`) |
| `openPositions` | number (long) | no | Count across all strategies/intervals |
| `dailyPnl` | number (BigDecimal) | no | Today's realised PnL (EUR) |
| `consecutiveLosses` | number (int) | no | Global counter for the primary strategy |
| `circuitBreakerOn` | boolean | no | True if any breaker has tripped |
| `reportedAt` | string (ISO 8601 `Instant`) | no | Snapshot timestamp |

### `PositionView`

| Field | JSON type | Nullable | Notes |
|-------|-----------|----------|-------|
| `id` | number (long) | no | Position ID |
| `pair` | string | no | e.g. `"BTC-EUR"` |
| `interval` | string | no | e.g. `"15m"` |
| `strategyName` | string | no | `StrategyType` enum name |
| `displayName` | string | no | Human-readable strategy label (see §2) |
| `side` | string | no | `"BUY"` or `"SELL"` |
| `entryPrice` | number | no | In quote currency |
| `quantity` | number | no | In base currency |
| `takeProfit` | number | no | TP price |
| `stopLoss` | number | no | SL price |
| `currentPrice` | number | no | Live market price |
| `unrealisedPnl` | number | no | In quote currency |
| `unrealisedPnlPct` | number | no | Percent |
| `signalReason` | string | no | Reason the position was opened |
| `openedAt` | string (ISO `LocalDateTime`) | no | Opening timestamp |

### `Trade`

(JPA entity serialized directly; no DTO wrapper.)

| Field | JSON type | Nullable | Notes |
|-------|-----------|----------|-------|
| `id` | number (long) | no | |
| `pair` | string | no | |
| `interval` | string | **yes** | Defaults to `"15m"` for legacy rows |
| `side` | string | no | `OrderSide` — `"BUY"` / `"SELL"` |
| `entryPrice` | number | no | |
| `exitPrice` | number | no | |
| `quantity` | number | no | |
| `pnl` | number | no | **Gross** realised PnL in quote currency (fees not deducted) |
| `pnlPct` | number | no | **Gross** realised PnL as percent |
| `netPnl` | number | **yes** | Net PnL after fees + slippage. Null while position is open. |
| `netPnlPct` | number | **yes** | Net PnL as percent. Null while open. |
| `entryFee` | number | no | Per-side fee at entry (0 when `trading.costs.fee-rate = 0.0`) |
| `exitFee` | number | no | Per-side fee at exit |
| `entrySlippage` | number | no | Per-side slippage cost at entry |
| `exitSlippage` | number | no | Per-side slippage cost at exit |
| `strategyName` | string | no | `StrategyType` enum name |
| `exitReason` | string | no | `TP_HIT` / `SL_HIT` / `SIGNAL_EXIT` / `MANUAL` |
| `tradingMode` | string | no | `"PAPER"` or `"LIVE"` |
| `executedAt` | string (ISO) | no | Entry timestamp |
| `closedAt` | string (ISO) | **yes** | Null while the trade is open |

> The nested `position` back-reference is **not** serialized.

### `TradeHistoryEntry`

Returned from `GET /api/strategies/{strategyType}/history`. All `Trade` fields plus enrichment from the parent `Position` (signal reason, TP/SL, openedAt) and two derived analytics (`holdingDurationSeconds`, `rMultiple`).

| Field | JSON type | Nullable | Notes |
|-------|-----------|----------|-------|
| `id` | number (long) | no | Trade ID |
| `pair` | string | no | |
| `interval` | string | no | e.g. `"15m"` |
| `side` | string | no | `OrderSide` — `"BUY"` / `"SELL"` |
| `entryPrice` | number | no | |
| `exitPrice` | number | **yes** | Null while still open (only closed trades are returned today, but the field is nullable) |
| `quantity` | number | no | |
| `pnl` | number | **yes** | Null while open |
| `pnlPct` | number | **yes** | Null while open |
| `strategyName` | string | no | `StrategyType` enum name |
| `exitReason` | string | **yes** | `TP_HIT` / `SL_HIT` / `SIGNAL_EXIT` / `MANUAL` |
| `tradingMode` | string | no | `"PAPER"` or `"LIVE"` |
| `executedAt` | string (ISO `LocalDateTime`) | no | Trade entry timestamp |
| `closedAt` | string (ISO `LocalDateTime`) | **yes** | Null while open |
| `entrySignalReason` | string | **yes** | From `Position.signalReason` (the reason the position was opened). Null for legacy trades without a linked position. |
| `takeProfit` | number | **yes** | From `Position.takeProfit` |
| `stopLoss` | number | **yes** | From `Position.stopLoss` |
| `openedAt` | string (ISO `LocalDateTime`) | **yes** | From `Position.openedAt` |
| `holdingDurationSeconds` | number (long) | **yes** | `closedAt − openedAt` in seconds. Null if either timestamp missing. |
| `rMultiple` | number | **yes** | `pnl / (|entryPrice − stopLoss| × quantity)` rounded to 4 dp. Null if any input missing or risk is zero. |
| `entryFee` | number | no | Same as `Trade.entryFee` (0 for legacy rows) |
| `exitFee` | number | no | Same as `Trade.exitFee` (0 for legacy rows) |
| `entrySlippage` | number | no | Same as `Trade.entrySlippage` (0 for legacy rows) |
| `exitSlippage` | number | no | Same as `Trade.exitSlippage` (0 for legacy rows) |
| `netPnl` | number | **yes** | Net PnL after all costs. For legacy rows `netPnl = pnl`. |
| `netPnlPct` | number | **yes** | Net PnL as percent. Same as `pnlPct` for legacy rows. |

### `SignalLog`

| Field | JSON type | Nullable | Notes |
|-------|-----------|----------|-------|
| `id` | number (long) | no | |
| `pair` | string | no | |
| `interval` | string | **yes** | Defaults to `"15m"` |
| `signalType` | string | no | `BUY` / `SELL` / `HOLD` |
| `confidence` | number | no | 0–100 |
| `reason` | string | no | Human-readable explanation |
| `emaShort` | number | **yes** | Strategy-specific indicator (see §6) |
| `emaLong` | number | **yes** | Strategy-specific indicator |
| `rsi` | number | **yes** | Strategy-specific indicator |
| `currentPrice` | number | no | Market price at signal time |
| `strategyName` | string | no | `StrategyType` enum name |
| `createdAt` | string (ISO) | no | |

### `TradingStats`

| Field | JSON type | Nullable | Notes |
|-------|-----------|----------|-------|
| `totalTrades` | number (int) | no | |
| `winningTrades` | number (int) | no | |
| `losingTrades` | number (int) | no | |
| `winRate` | number | no | 0–100 (percent) |
| `totalPnl` | number | no | **Gross** sum |
| `averageWin` | number | no | |
| `averageLoss` | number | no | Negative value |
| `bestTrade` | number | no | |
| `worstTrade` | number | no | |
| `expectancy` | number | no | Gross expectancy: `(winRate × avgWin) + ((1 − winRate) × avgLoss)` in EUR/trade |
| `netPnl` | number | no | Net PnL (after fees + slippage). Equals `totalPnl` when costs are zero. |
| `totalFees` | number | no | Sum of all entry+exit fees |
| `totalSlippage` | number | no | Sum of all entry+exit slippage |
| `feeDragPct` | number | no | `(totalFees + totalSlippage) / |totalPnl| × 100`. 0 when no costs or no gross PnL. |
| `netExpectancy` | number | no | Net expectancy in EUR/trade |

### `PnlBreakdown`

| Field | JSON type | Nullable | Notes |
|-------|-----------|----------|-------|
| `daily` | number | no | **Gross** PnL for the current day |
| `weekly` | number | no | **Gross** PnL for the current week |
| `monthly` | number | no | **Gross** PnL for the current month |
| `allTime` | number | no | **Gross** PnL all-time |
| `dailyNet` | number | no | Net daily PnL (after fees). Equals `daily` when costs are zero. |
| `weeklyNet` | number | no | Net weekly PnL |
| `monthlyNet` | number | no | Net monthly PnL |
| `allTimeNet` | number | no | Net all-time PnL |

### `FearGreedResponse`

Returned from `GET /api/market/fear-greed`. **The endpoint returns `204 No Content` when unavailable — clients MUST handle the null/missing case.**

| Field | JSON type | Nullable | Notes |
|-------|-----------|----------|-------|
| `value` | number (int) | no | 0 (Extreme Fear) – 100 (Extreme Greed) |
| `classification` | string | no | `"Extreme Fear"` / `"Fear"` / `"Neutral"` / `"Greed"` / `"Extreme Greed"` |
| `timestamp` | number (long) | no | Unix epoch **seconds** |

### `ConfigUpdateRequest`

PATCH semantics — every field is optional, only non-null fields are applied. All numeric fields must be positive (`@Positive` validation).

| Field | JSON type | Notes |
|-------|-----------|-------|
| `maxPositionPct` | number | % of balance per position |
| `maxConcurrentPositions` | number (int) | |
| `maxDailyLossPct` | number | Circuit-breaker threshold |
| `maxConsecutiveLosses` | number (int) | Circuit-breaker threshold |
| `takeProfitPct` | number | |
| `stopLossPct` | number | |
| `emaShortPeriod` | number (int) | |
| `emaLongPeriod` | number (int) | |
| `rsiPeriod` | number (int) | |
| `rsiOverbought` | number (int) | |
| `rsiOversold` | number (int) | |
| `paperBalance` | number | Paper-mode balance override |

### `PairInfo`

Ad-hoc map returned by `GET /api/pairs`.

| Field | JSON type | Notes |
|-------|-----------|-------|
| `pair` | string | e.g. `"BTC-EUR"` |
| `baseAsset` | string | e.g. `"BTC"` |
| `quoteAsset` | string | e.g. `"EUR"` |

### `IntervalInfo`

Ad-hoc map returned by `GET /api/intervals`.

| Field | JSON type | Notes |
|-------|-----------|-------|
| `minutes` | number (int) | e.g. 15 |
| `label` | string | e.g. `"15m"` — canonical query-param value |
| `displayName` | string | e.g. `"15 minutes"` |

### `StrategyInfo`

Ad-hoc map returned by `GET /api/strategies`.

| Field | JSON type | Notes |
|-------|-----------|-------|
| `name` | string | `StrategyType` enum name |
| `displayName` | string | Human-readable label (see §2) |
| `openPositions` | number | |
| `dailyPnl` | number | |
| `consecutiveLosses` | number | |
| `circuitBreakerActive` | boolean | |

### `SignalSummaryRow`

Ad-hoc map returned by `GET /api/signals/summary` (one row per strategy × signal type).

| Field | JSON type | Notes |
|-------|-----------|-------|
| `strategy` | string | `StrategyType` enum name |
| `signalType` | string | `BUY` / `SELL` / `HOLD` |
| `count` | number | |

### `TripleStats`

Returned by `GET /api/stats/all-triples`. One row per configured `(pair, interval, strategy)` triple. All numeric fields are zero when no closed trades exist for the triple.

| Field | JSON type | Nullable | Notes |
|-------|-----------|----------|-------|
| `pair` | string | no | e.g. `"BTC-EUR"` |
| `interval` | string | no | e.g. `"15m"` |
| `strategy` | string | no | `StrategyType` enum name |
| `displayName` | string | no | Human-readable label (see §2) |
| `totalTrades` | number (int) | no | Closed trades counted |
| `winningTrades` | number (int) | no | `pnl > 0` |
| `losingTrades` | number (int) | no | `pnl <= 0` (ties counted as losses, matches `TradingStats`) |
| `winRate` | number | no | 0–100, two decimals |
| `totalPnl` | number | no | Sum of `pnl` |
| `averageWin` | number | no | Mean of positive-pnl trades |
| `averageLoss` | number | no | Mean of non-positive-pnl trades (negative) |
| `bestTrade` | number | no | Max `pnl` |
| `worstTrade` | number | no | Min `pnl` |
| `expectancy` | number | no | `(winRate/100 × avgWin) + ((1 − winRate/100) × avgLoss)` |
| `openPositions` | number (int) | no | Current `status=OPEN` count for this triple |
| `circuitBreakerActive` | boolean | no | `dailyCircuitBreakerTripped || consecutiveCircuitBreakerTripped` |
| `netPnl` | number | no | Net PnL after fees + slippage. Equals `totalPnl` when costs are zero. |
| `totalCosts` | number | no | Sum of all fees and slippage for this triple |
| `feeDragPct` | number | no | `totalCosts / |totalPnl| × 100`. 0 when no costs or no gross PnL. |
| `netExpectancy` | number | no | Net expectancy in EUR/trade |
| `enabled` | boolean | no | `false` when the triple is currently soft-disabled (no new entries; existing positions still monitored). Sourced from `trading.disabled_triples` via `TripleConfigService`. |

### `TripleDisableRequest`

Optional request body for `POST /api/triples/{pair}/{strategy}/{interval}/disable`.

| Field | JSON type | Nullable | Notes |
|-------|-----------|----------|-------|
| `reason` | string | yes | Free-form audit text. Surfaces in `/api/triples/disabled` and the `bot_events` activity feed. |

### `DisabledTripleResponse`

Returned by `GET /api/triples/disabled` and `POST /api/triples/.../disable`.

| Field | JSON type | Nullable | Notes |
|-------|-----------|----------|-------|
| `pair` | string | no | e.g. `"BTC-EUR"` |
| `strategy` | string | no | `StrategyType` enum name |
| `interval` | string | no | e.g. `"15m"` |
| `disabledAt` | string (ISO 8601) | no | UTC timestamp when the row was inserted. |
| `reason` | string | yes | Free-form text supplied at disable time. |

### `TodaySummary`

Returned by `GET /api/today`. Single payload for the "Today" UI tab.

| Field | JSON type | Nullable | Notes |
|-------|-----------|----------|-------|
| `date` | string (ISO `LocalDate`) | no | Server-local current date — basis for "today" |
| `startOfDay` | string (ISO `LocalDateTime`) | no | `date.atStartOfDay()` — lower bound for `closedAt` |
| `generatedAt` | string (ISO `LocalDateTime`) | no | Snapshot timestamp |
| `totalTrades` | number (int) | no | Closed trades since `startOfDay` (across all triples) |
| `winningTrades` | number (int) | no | `pnl > 0` count |
| `losingTrades` | number (int) | no | `pnl <= 0` count (matches `TradingStats` convention) |
| `winRate` | number | no | 0–100, two decimals |
| `grossPnl` | number | no | Sum of `pnl` |
| `netPnl` | number | no | Sum of `netPnl` (falls back to `pnl` for legacy rows with null `netPnl`) |
| `totalFees` | number | no | Sum of all entry+exit fees today |
| `totalSlippage` | number | no | Sum of all entry+exit slippage today |
| `feeDragPct` | number | no | `totalCosts / |grossPnl| × 100`, 0 when no gross PnL |
| `bestTrade` | number | no | Max `pnl` |
| `worstTrade` | number | no | Min `pnl` |
| `averageWin` | number | no | Mean `pnl` of winners (0 if none) |
| `averageLoss` | number | no | Mean `pnl` of losers (0 if none, negative when present) |
| `expectancy` | number | no | `(winRateFrac × avgWin) + ((1 − winRateFrac) × avgLoss)` |
| `positionsOpenedToday` | number (int) | no | Count of currently-OPEN positions whose `openedAt >= startOfDay` |
| `positionsClosedToday` | number (int) | no | Same as `totalTrades` (alias for clarity) |
| `openPositionsNow` | number (int) | no | Total currently-OPEN positions across the whole bot |
| `byTriple` | [`TodayTripleRow[]`](#todaytriplerow) | no | One row per `(pair, interval, strategy)` that traded today, sorted by `netPnl DESC` |
| `trades` | [`Trade[]`](#trade) | no | Closed trades today, newest-first by `closedAt` |
| `openPositions` | [`PositionView[]`](#positionview) | no | Positions opened today still `OPEN`, enriched with live price |

### `TodayTripleRow`

Per-triple roll-up of today's closed trades, embedded in [`TodaySummary.byTriple`](#todaysummary).

| Field | JSON type | Notes |
|-------|-----------|-------|
| `pair` | string | e.g. `"BTC-EUR"` |
| `interval` | string | e.g. `"15m"` |
| `strategy` | string | `StrategyType` enum name |
| `displayName` | string | Human-readable strategy label (see §2) |
| `totalTrades` | number (int) | Trades closed today by this triple |
| `winningTrades` | number (int) | `pnl > 0` |
| `losingTrades` | number (int) | `pnl <= 0` |
| `grossPnl` | number | Sum of `pnl` for this triple |
| `netPnl` | number | Sum of effective `netPnl` |
| `totalCosts` | number | Sum of all fees + slippage |

### `BotEvent`

Returned by `GET /api/activity`. Persisted into `trading.bot_events` at the point each event occurs.

| Field | JSON type | Nullable | Notes |
|-------|-----------|----------|-------|
| `id` | number (long) | no | |
| `type` | string | no | `BotEventType` enum name (see §2) |
| `severity` | string | no | `BotEventSeverity` enum name (see §2) |
| `pair` | string | **yes** | Null for bot-wide events (`BOT_STOPPED`, `BOT_RESUMED`, `CONFIG_CHANGED`) |
| `interval` | string | **yes** | Null for bot-wide events |
| `strategy` | string | **yes** | Null for bot-wide events |
| `title` | string | no | Short human-readable headline |
| `detail` | string | **yes** | Longer explanatory text when relevant |
| `metadata` | string | **yes** | Raw JSON string payload (currently populated for `CONFIG_CHANGED`) |
| `createdAt` | string (ISO `LocalDateTime`) | no | |

### `CurrentSignal`

Returned by `GET /api/signals/current`. One row per configured `(pair, interval, strategy)` triple. Indicator slot fields (`emaShort`, `emaLong`, `rsi`) follow the **same per-strategy mapping as `SignalLog`** — see §6.

| Field | JSON type | Nullable | Notes |
|-------|-----------|----------|-------|
| `pair` | string | no | e.g. `"BTC-EUR"` |
| `interval` | string | no | e.g. `"15m"` |
| `strategy` | string | no | `StrategyType` enum name |
| `displayName` | string | no | Human-readable strategy label (see §2) |
| `signalType` | string | **yes** | `BUY` / `SELL` / `HOLD`. Null when no signal has been evaluated for this triple yet. |
| `confidence` | number | **yes** | 0–100. Null when `signalType` is null. |
| `reason` | string | **yes** | Human-readable explanation built by the strategy. Null when no signal yet. |
| `currentPrice` | number | **yes** | Market price at evaluation time. Null when no signal yet. |
| `emaShort` | number | **yes** | Strategy-specific indicator slot (see §6). |
| `emaLong` | number | **yes** | Strategy-specific indicator slot. |
| `rsi` | number | **yes** | Strategy-specific indicator slot. |
| `evaluatedAt` | string (ISO `LocalDateTime`) | **yes** | `SignalLog.createdAt` of the latest row. Null when no signal yet. |

---

### `CandleBar`

Returned by `GET /api/candles`. Each element is one OHLCV bar for the requested `(pair, interval)`.

| Field | JSON type | Notes |
|-------|-----------|-------|
| `time` | number | Unix epoch **seconds** (not milliseconds). Matches lightweight-charts `UTCTimestamp`. |
| `open` | number | Opening price |
| `high` | number | High price |
| `low` | number | Low price |
| `close` | number | Closing price |
| `volume` | number | Volume in base asset |

### `SentimentResponse`
Response of `GET /api/market/sentiment`.

| Field | Type | Notes |
|-------|------|-------|
| `pair` | string | e.g. `"BTC-EUR"` |
| `source` | `SentimentSource` | `REDDIT` / `CRYPTOPANIC` / `COMBINED` |
| `interval` | string | The interval label actually used for the window (e.g. `"1h"`) |
| `score` | number \| null | [-1, +1], volume-weighted mean; `null` when below `sampleSize` threshold |
| `volume` | number | Sum of per-row volumes in the window (Reddit upvotes / CP vote totals) |
| `sampleSize` | number | Row count in the window |
| `capturedAt` | string (ISO 8601) | When the aggregate was computed (ISO instant) |
| `stale` | boolean | `true` when sample fell below threshold and `score == null` |
| `subScores` | `SentimentSubScore[] \| null` | Populated only when `source === "COMBINED"` |

### `SentimentSubScore`

| Field | Type | Notes |
|-------|------|-------|
| `source` | `SentimentSource` | `REDDIT` or `CRYPTOPANIC` |
| `score` | number \| null | Per-source [-1, +1] |
| `volume` | number | |
| `sampleSize` | number | |

### `RedditPostDto`
Nested inside `RedditIngestRequest`.

| Field | Type | Validation | Notes |
|-------|------|------------|-------|
| `externalId` | string | required | Reddit post id, e.g. `"t3_abc123"` |
| `subreddit` | string | required | |
| `title` | string | required | |
| `body` | string | | Selftext; may be empty |
| `score` | int | ≥ 0 | Reddit upvotes |
| `numComments` | int | ≥ 0 | |
| `createdUtc` | string (ISO 8601) | required | |
| `permalink` | string | required | |
| `pairHints` | `string[]` | required | e.g. `["BTC-EUR", "ETH-EUR"]`; empty for generic posts |

### `RedditIngestRequest`

| Field | Type | Notes |
|-------|------|-------|
| `posts` | `RedditPostDto[]` | up to `sentiment.ingest.max-batch-size` |
| `scrapedAt` | string (ISO 8601 instant) | |

### `CryptoPanicVotesDto`
All fields default to 0; the derived score treats zero-vote posts as neutral.

| Field | Type | Notes |
|-------|------|-------|
| `positive` | int | ≥ 0 |
| `negative` | int | ≥ 0 |
| `important` | int | ≥ 0 |
| `liked` | int | ≥ 0 |
| `disliked` | int | ≥ 0 |

### `CryptoPanicPostDto`

| Field | Type | Notes |
|-------|------|-------|
| `externalId` | string | required |
| `title` | string | required |
| `url` | string | required |
| `votes` | `CryptoPanicVotesDto` | required |
| `currencyCodes` | `string[]` | uppercase tickers, e.g. `["BTC", "ETH"]` |
| `publishedAt` | string (ISO 8601) | required |

### `CryptoPanicIngestRequest`

| Field | Type | Notes |
|-------|------|-------|
| `posts` | `CryptoPanicPostDto[]` | up to `sentiment.ingest.max-batch-size` |
| `scrapedAt` | string (ISO 8601 instant) | |

### `IngestResponse`
Returned from both ingest endpoints. `received == accepted + deduped + filtered`.

| Field | Type | Notes |
|-------|------|-------|
| `received` | int | Posts in the request |
| `accepted` | int | Posts that became at least one DB row |
| `deduped` | int | Posts skipped because already present |
| `filtered` | int | Posts dropped by pre-filter (Reddit: min-score / min-comments / max-age; CryptoPanic: unknown currency) |
| `classified` | int | Posts that reached the Haiku classifier; always 0 for CryptoPanic |

### `BacktestRequest`

Body of `POST /api/backtest/run`.

| Field | JSON type | Nullable | Notes |
|-------|-----------|----------|-------|
| `pair` | string | no | e.g. `"BTC-EUR"` — must be in `trading.pairs` |
| `strategy` | string | no | `StrategyType` enum name |
| `interval` | string | no | e.g. `"15m"` |
| `startDate` | string (ISO 8601, no TZ) | no | Inclusive |
| `endDate` | string (ISO 8601, no TZ) | no | Inclusive; must be after `startDate` |
| `startingBalance` | number | yes | Defaults to `trading.strategy-balances[pair][strategy]` then `trading.paper-balance` |
| `paramOverrides` | object | yes | Recognized keys: `emaShortPeriod`, `emaLongPeriod`, `rsiPeriod`, `rsiOverbought`, `rsiOversold`, `takeProfitPct`, `stopLossPct`, `maxPositionPct`, `feeRate`, `slippageRate`. Unknown keys are ignored. |
| `label` | string | yes | Free-form; auto-generated from triple + dates if omitted. |
| `notes` | string | yes | Free-form |

### `BacktestRunSummary`

Returned by `GET /api/backtest/runs` and `PATCH /api/backtest/runs/{id}`. Excludes the heavy `trades` and `equityCurve` payloads.

| Field | JSON type | Nullable | Notes |
|-------|-----------|----------|-------|
| `id` | string (UUID) | no | |
| `pair` | string | no | |
| `strategy` | string | no | `StrategyType` |
| `interval` | string | no | |
| `startDate` | string (ISO 8601) | no | |
| `endDate` | string (ISO 8601) | no | |
| `startingBalance` | number | no | |
| `stats` | [`BacktestStats`](#backteststats) | no | |
| `label` | string | yes | |
| `createdAt` | string (ISO 8601) | no | |

### `BacktestRunDetail`

Returned by `POST /api/backtest/run` and `GET /api/backtest/runs/{id}`. Superset of `BacktestRunSummary`.

| Field | JSON type | Nullable | Notes |
|-------|-----------|----------|-------|
| (all `BacktestRunSummary` fields) | | | |
| `params` | object | no | Override map applied to this run. May be empty. |
| `trades` | [`SimulatedTrade[]`](#simulatedtrade) | no | Closed trades in execution order. |
| `equityCurve` | [`EquityPoint[]`](#equitypoint) | no | One point per bar in the window after warmup. |
| `notes` | string | yes | |

### `BacktestStats`

Aggregated metrics for a run. Combines the fields of [`TradingStats`](#tradingstats) with backtest-only risk/quality metrics. All numeric fields are JSON numbers (BigDecimal serialized).

| Field | JSON type | Notes |
|-------|-----------|-------|
| `totalTrades`, `winningTrades`, `losingTrades` | int | |
| `winRate` | number | 0–100 |
| `totalPnl`, `averageWin`, `averageLoss`, `bestTrade`, `worstTrade`, `expectancy` | number | Gross |
| `netPnl`, `totalFees`, `totalSlippage`, `feeDragPct`, `netExpectancy` | number | Net (after fees + slippage) |
| `sharpeRatio` | number | `mean(barReturn) / stddev(barReturn) × sqrt(252)`. > 1 = decent, > 2 = great. |
| `maxDrawdown` | number | EUR; largest peak-to-trough on the equity curve. |
| `maxDrawdownPct` | number | Same as fraction of the peak. |
| `maxDrawdownDurationBars` | int | Bars from the peak to the trough. |
| `profitFactor` | number | `Σ wins / Σ |losses|`. > 1 = profitable; > 1.5 = healthy. Returns `999` when there are wins but no losses. |
| `maxConsecutiveLosses` | int | Longest losing streak. |
| `tradesPerMonth` | number | `nTrades / months in window`. |
| `tStatistic` | number | t-stat of mean trade net P&L. `t > 2` ≈ 95% confidence the edge is real (with caveats). |
| `pnlStdDev` | number | Per-trade net P&L stddev. |

### `SimulatedTrade`

A single closed trade produced by a backtest replay. Lives inside `BacktestRunDetail.trades`. Never persisted to `trading.trades`.

| Field | JSON type | Notes |
|-------|-----------|-------|
| `sequence` | int | 1-based order in the run. |
| `side` | string | `"BUY"` (only longs in v1). |
| `entryPrice`, `exitPrice`, `quantity` | number | |
| `executedAt`, `closedAt` | string (ISO 8601) | |
| `pnl`, `pnlPct` | number | Gross. |
| `entryFee`, `exitFee`, `entrySlippage`, `exitSlippage` | number | |
| `netPnl`, `netPnlPct` | number | After all costs. |
| `exitReason` | string | `TP_HIT` / `SL_HIT` / `SIGNAL_EXIT` / `BACKTEST_END` |
| `entrySignalReason` | string | The strategy's reason text at entry. |

### `EquityPoint`

One sample on the equity curve.

| Field | JSON type | Notes |
|-------|-----------|-------|
| `timestamp` | string (ISO 8601) | Bar end time, UTC. |
| `equity` | number | `balance + unrealizedPnl` at this bar's close. |
| `drawdown` | number | EUR; `peakEquity − equity`. |
| `drawdownPct` | number | Fraction of peak. |

### `WalkForwardResult`

Returned by `POST /api/backtest/walk-forward`.

| Field | JSON type | Notes |
|-------|-----------|-------|
| `windows` | [`BacktestRunSummary[]`](#backtestrunsummary) | One element per sub-window that ran successfully, in chronological order. Each element is also persisted as its own row in `trading.backtest_runs`. May be shorter than the requested window count if some sub-windows were skipped (see `skippedWindows`). |
| `skippedWindows` | `SkippedWindow[]` | Sub-windows the simulator could not run — typically because no candles exist in that range (15m history caps at ~47 days). Each entry: `{ "index": int (1-based), "startDate": ISO 8601, "endDate": ISO 8601, "reason": string }`. |
| `varianceMetrics.winRateStdDev` | number | Stddev of `winRate` across successful windows. |
| `varianceMetrics.expectancyStdDev` | number | Stddev of gross `expectancy`. |
| `varianceMetrics.netPnlStdDev` | number | Stddev of `netPnl`. |
| `varianceMetrics.consistencyVerdict` | string | `STABLE` (CV < 0.30) / `REGIME_DEPENDENT` (0.30–0.70) / `WILDLY_VARYING_HIGH_VARIANCE` (CV ≥ 0.70, positive mean) / `WILDLY_VARYING_NEGATIVE` (mean ≤ 0). Heuristic — eyeball the per-window stats too. |

---

## 6. Strategy-specific indicator overloading

`SignalLog.emaShort`, `emaLong`, and `rsi` are **reused as generic slots** across strategies. The UI maps them to correct labels via `revolut-trading-bot-ui/src/utils/strategyMeta.ts`:

| Strategy | `emaShort` slot | `emaLong` slot | `rsi` slot |
|----------|-----------------|----------------|------------|
| `EMA_CROSSOVER` | EMA9 | EMA21 | RSI |
| `MACD` | MACD line | Signal line | Histogram |
| `BOLLINGER` | Upper band | Lower band | %B (0–100) |
| `RSI_MOMENTUM` | null | null | RSI |
| `STOCH_RSI` | StochRSI × 100 | null | RSI |
| `TRIPLE_EMA` | EMA5 | EMA34 | EMA13 |
| `PARABOLIC_SAR` | SAR value | null | Price − SAR % |
| `ADX_DI` | +DI | −DI | ADX |
| `CCI` | null | null | CCI |
| `MFI` | null | null | MFI |
| `DONCHIAN` | Upper channel | Lower channel | Width % |
| `ICHIMOKU` | Tenkan-sen | Kijun-sen | Span A |
| `SUPERTREND` | Supertrend line | ATR value | Distance % `(close − supertrend) / close × 100` (positive = bullish) |
| `REDDIT_SENTIMENT` | Aggregate score [-1, +1] | Window volume (upvotes) | Sample size (# posts) |
| `CRYPTOPANIC_SENTIMENT` | Aggregate score [-1, +1] | Window volume (vote totals) | Sample size (# posts) |
| `COMBINED_SENTIMENT` | Blended score [-1, +1] | Total volume across sources | 0 = sources agree, 1 = disagree |

If you add a new strategy, update **both** the backend signal builder and this mapping.

---

## 7. UI polling cadence (informational)

Used by backend to estimate read load.

| Endpoint | Interval | Source hook |
|----------|----------|-------------|
| `GET /api/status` | 10s | `useBotStatus` |
| `GET /api/strategies` | 15s | `useStrategies` |
| `GET /api/strategies/{n}/positions` | 15s | `useStrategyDetail`, `useAllStrategyPositions` |
| `GET /api/strategies/{n}/trades` | 30s | `useStrategyDetail`, `useAllStrategyTrades` |
| `GET /api/strategies/{n}/history` | 60s | `useHistory` (History tab) |
| `GET /api/strategies/{n}/signals` | 30s | `useStrategyDetail` |
| `GET /api/strategies/{n}/stats` | 60s | `useStrategyDetail`, `useAllStrategyStats` |
| `GET /api/strategies/{n}/pnl` | 60s | `useStrategyDetail` |
| `GET /api/signals/summary` | 60s | `useSignalSummary` |
| `GET /api/signals/current` | 30s | `useCurrentSignals` — Signals tab + Consensus tab |
| `GET /api/positions/live` | 15s | `useLivePositions` (Positions tab) |
| `GET /api/stats/all-triples` | 30s | `useAllTripleStats` — Leaderboard + Cross-Interval |
| `GET /api/strategies/{n}/trades` (fanned × 5 intervals) | 30s | `useAllIntervalTrades` (Cross-Interval tab) |
| `GET /api/activity` | 15s | `useActivityFeed` (Activity tab) |
| `GET /api/today` | 30s | `useToday` (Today tab) |
| `GET /api/market/fear-greed` | 5 min | `useFearGreed` |
| `GET /api/market/sentiment` | 2 min | `useSentiment` |
| `POST /api/sentiment/ingest/{reddit,cryptopanic}` | scraper-driven (5 / 15 min) | external `sentiment-scraper` microservice |
| `GET /api/candles` | 30s | `useCandles` (Charts tab) |
| `GET /api/pairs`, `GET /api/intervals` | once (static) | `usePairs`, `useIntervals` |

All queries run only while the tab is visible (`refetchIntervalInBackground: false`).

---

## 8. Cross-reference appendix

### Backend

| What | Path |
|------|------|
| All `/api/**` endpoints | `revolut-trading-bot/src/main/java/com/stefo/revolut_trading_bot/controller/DashboardController.java` |
| Response DTOs | `revolut-trading-bot/src/main/java/com/stefo/revolut_trading_bot/model/dto/` |
| Exposed entities | `revolut-trading-bot/src/main/java/com/stefo/revolut_trading_bot/model/entity/{Trade,Position,SignalLog}.java` |
| Enums | `revolut-trading-bot/src/main/java/com/stefo/revolut_trading_bot/model/enums/` |
| CORS | `revolut-trading-bot/src/main/java/com/stefo/revolut_trading_bot/config/CorsConfig.java` |
| Jackson date format | `revolut-trading-bot/src/main/java/com/stefo/revolut_trading_bot/config/JacksonConfig.java` |
| Defaults (port, pairs, intervals) | `revolut-trading-bot/src/main/resources/application.yml` |

### Frontend

| What | Path |
|------|------|
| All TS types | `revolut-trading-bot-ui/src/api/client.ts` |
| API clients | `revolut-trading-bot-ui/src/api/{pairs,intervals,status,strategies,signals,fearGreed}.ts` |
| Query hooks | `revolut-trading-bot-ui/src/hooks/*.ts` |
| Vite proxy | `revolut-trading-bot-ui/vite.config.ts` |
| Indicator-slot mapping | `revolut-trading-bot-ui/src/utils/strategyMeta.ts` |

---

## 9. Update checklist

Whenever you change the contract, walk this list:

- [ ] **New/renamed/deleted endpoint** → update §4, update the relevant `src/api/*.ts` client, update `useXxx` hook
- [ ] **DTO field added/removed/renamed/retyped** → update §5 **and** the matching interface in `src/api/client.ts`
- [ ] **New enum value** → update §2 (and §6 if it's a strategy)
- [ ] **New strategy** → update §2 display-name table **and** §6 indicator-slot table
- [ ] **Query-param default changed** → update §3 and the relevant row in §4
- [ ] **Polling cadence changed** → update §7
- [ ] **Auth/CORS/error-format change** → update §1
