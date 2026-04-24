# API Contract — Revolut Trading Bot

> **Single source of truth** for the HTTP contract between the Spring Boot backend (`revolut-trading-bot/`) and the React/TypeScript UI (`revolut-trading-bot-ui/`). When either side changes an endpoint, DTO, or enum, this document MUST be updated in the same PR.
>
> **Scope**: only the frontend-facing `/api/**` surface. The `/test/**` controller is dev-only and not part of the contract.
>
> **Tier 2 note:** The Consensus and Cross-Interval UI tabs ship without any contract changes — both aggregate existing `/api/signals/current` and `/api/stats/all-triples` responses client-side, plus a 5-way fan-out of `/api/strategies/{name}/trades` for the cross-interval equity chart.
>
> **Phase 13 (leverage):** Contract is additive. `TradingVehicle` enum added; `?vehicle=` query param added to strategy-scoped endpoints (default `SPOT`, so existing UI calls work unchanged); `Trade.exitReason` adds `LIQUIDATED`; `BotEventType` adds `POSITION_LIQUIDATED`; `PositionView` / `Trade` / `TradeHistoryEntry` / `TripleStats` / `CurrentSignal` gain leverage fields (nullable for `SPOT`); new `GET /api/vehicles`.

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

### `StrategyType` (13 values)

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

### Other enums

| Enum | Values |
|------|--------|
| `SignalType` | `BUY`, `SELL`, `HOLD` |
| `OrderSide` | `BUY`, `SELL` |
| `OrderStatus` | `OPEN`, `CLOSED` |
| `TradingMode` | `PAPER`, `LIVE` |
| `TradingVehicle` | `SPOT` (leverage=1), `LEV_3X`, `LEV_5X`, `LEV_10X` |
| `Trade.exitReason` (free-form string) | `TP_HIT`, `SL_HIT`, `SIGNAL_EXIT`, `MANUAL`, `LIQUIDATED` |
| `BotEventType` | `POSITION_OPENED`, `POSITION_CLOSED`, `POSITION_LIQUIDATED`, `CIRCUIT_BREAKER_TRIPPED`, `CIRCUIT_BREAKER_RESET`, `BOT_STOPPED`, `BOT_RESUMED`, `CONFIG_CHANGED` |
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
| `vehicle` | `TradingVehicle` | optional | `SPOT` | Filter by trading vehicle (Phase 13 — leveraged paper trading). Backward-compatible: endpoints that don't accept `?vehicle=` yet simply return all vehicles mixed. |
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

#### `GET /api/vehicles`
- Response: `VehicleInfo[]` — `{ name: TradingVehicle, leverage: number, active: boolean }`
- `active=true` for SPOT always; for `LEV_*X` when `trading.leverage.enabled=true` and the ratio is listed in `trading.leverage.ratios`.
- UI: fetched once, cached with `staleTime: Infinity`.

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
- UI polling: 30s (Leaderboard tab)

---

### 4.5 Activity

#### `GET /api/activity`
- Query: `limit?` (default `100`, hard-capped at 500), `types?` — optional comma-separated list of [`BotEventType`](#botevent) values (e.g. `?types=POSITION_OPENED,CIRCUIT_BREAKER_TRIPPED`)
- Response: [`BotEvent[]`](#botevent) — reverse-chronological audit rows from `trading.bot_events`.
- Events persisted at the point they occur: position opens/closes, circuit-breaker trip/reset transitions, bot stop/resume, config changes.
- UI polling: 15s (Activity tab)

---

### 4.6 Market

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

### 4.8 Legacy / global (no interval scoping)

These endpoints predate multi-interval support and are retained for back-compat. New UI code should prefer the per-strategy endpoints in §4.3.

| Endpoint | Query | Response |
|----------|-------|----------|
| `GET /api/positions` | `pair?` | [`PositionView[]`](#positionview) |
| `GET /api/trades` | `limit?` (default 50), `pair?` | [`Trade[]`](#trade) |
| `GET /api/stats` | — | [`TradingStats`](#tradingstats) |
| `GET /api/pnl` | — | [`PnlBreakdown`](#pnlbreakdown) |

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
| `vehicle` | `TradingVehicle` | no | `SPOT` / `LEV_3X` / `LEV_5X` / `LEV_10X` |
| `leverage` | number (int) | no | 1 for SPOT; 3/5/10 for leveraged |
| `collateral` | number | **yes** | EUR locked as margin (null for SPOT) |
| `notional` | number | **yes** | `collateral × leverage` (null for SPOT) |
| `liquidationPrice` | number | **yes** | Null for SPOT |
| `currentMarginRatio` | number | **yes** | Remaining equity as a fraction of initial collateral (1.0 = intact, 0.0 = liquidated). Null for SPOT. |
| `fundingFeesAccrued` | number | **yes** | Total perp-style funding accrued on this position (leveraged only) |

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
| `exitReason` | string | no | `TP_HIT` / `SL_HIT` / `SIGNAL_EXIT` / `MANUAL` / `LIQUIDATED` |
| `tradingMode` | string | no | `"PAPER"` or `"LIVE"` |
| `vehicle` | `TradingVehicle` | no | `SPOT` / `LEV_3X` / `LEV_5X` / `LEV_10X` |
| `leverage` | number (int) | no | 1 for SPOT |
| `collateral` | number | **yes** | Null for SPOT |
| `fundingFees` | number | no | Total funding paid over the position's life (0 for SPOT) |
| `liquidated` | boolean | no | `true` when closed via liquidation |
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
| `GET /api/market/fear-greed` | 5 min | `useFearGreed` |
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
