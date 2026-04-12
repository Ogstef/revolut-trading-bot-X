# Revolut X Trading Bot — Project Blueprint

## Overview

Automated cryptocurrency trading bot in Java/Spring Boot that uses the Revolut X REST API. Starts in PAPER mode (simulated trades), validates the strategy, then optionally switches to LIVE mode with real money.

## Tech Stack

- **Java 21**, **Spring Boot 4.0.5**, **Maven**
- **PostgreSQL 16** (Docker, port 5432, db: `trading_bot`, user: `trading_bot`, pw: `secret`)
- **Ta4j 0.16** — technical analysis indicators (EMA, RSI, MACD, etc.)
- **BouncyCastle 1.83** — Ed25519 request signing
- **OkHttp 4.12** — HTTP client for Revolut X API
- **Flyway** — database migrations
- **MapStruct 1.6.3** — DTO/entity mapping
- **Lombok** — boilerplate reduction
- **Spock 2.4-M4 + Groovy** — testing framework (team standard)
- **Base package**: `com.stefo.revolut_trading_bot`

## Revolut X API

### Base URL
`https://revx.revolut.com/api/1.0`

### Authentication
Custom Ed25519 signature scheme on every authenticated request.

**Headers required:**
- `X-Revx-API-Key` — 64-char alphanumeric key (env var: `REVOLUT_API_KEY`)
- `X-Revx-Timestamp` — Unix epoch milliseconds
- `X-Revx-Signature` — Base64-encoded Ed25519 signature

**Message to sign** (concatenate WITHOUT separators):
```
{timestamp}{HTTP_METHOD}{path}{queryString}{body}
```

- Path starts from `/api` (e.g., `/api/1.0/orders/active`)
- Query string excludes the `?` prefix
- Body is minified JSON (empty string for GET)
- Private key: Ed25519 PEM file (env var: `REVOLUT_PRIVATE_KEY_PATH`)

**Example:**
```
1765360896219POST/api/1.0/orders{"client_order_id":"uuid","symbol":"BTC-USD","side":"BUY","order_configuration":{"limit":{"base_size":"0.1","price":"90000.1"}}}
```

### Endpoints

All endpoints are **authenticated** (require `X-Revx-Timestamp` + `X-Revx-Signature` headers).
All responses are wrapped: `{ "data": <payload> }` — the client unwraps this automatically.

| Method | Path | Query params | Description |
|--------|------|--------------|-------------|
| GET | `/candles/{symbol}` | `interval` (int, minutes: 1/5/15/30/60/240/1440…), `since` (epoch ms), `until` (epoch ms) | Historical OHLCV candles. Response field is `start` (not `timestamp`). Default returns last 5000 candles. |
| GET | `/tickers` | `symbols` (comma-separated) | Real-time bid, ask, mid, last_price per symbol |
| GET | `/order-book/{symbol}` | `limit` (1–20, default 20) | Order book depth. PriceLevel fields: `p`=price, `q`=quantity |
| GET | `/balances` | — | Account balances |
| POST | `/orders` | — | Place order. `side` must be lowercase `"buy"`/`"sell"` |
| GET | `/orders/{venue_order_id}` | — | Full order detail |
| DELETE | `/orders/{venue_order_id}` | — | Cancel order |

**Rate limit:** 1000 requests/day for limit orders. 1000 requests/minute general.

### Order Body Structures

**Market order:**
```json
{
  "client_order_id": "uuid",
  "symbol": "BTC-EUR",
  "side": "buy",
  "order_configuration": {
    "market": { "quote_size": "100.00" }
  }
}
```

**Limit order:**
```json
{
  "client_order_id": "uuid",
  "symbol": "BTC-EUR",
  "side": "buy",
  "order_configuration": {
    "limit": { "base_size": "0.001", "price": "62000.00" }
  }
}
```

**Place order response** (`POST /orders`):
```json
{ "data": { "venue_order_id": "uuid", "client_order_id": "uuid", "state": "pending_new" } }
```

---

## Architecture

### Package Structure
```
com.stefo.revolut_trading_bot/
├── config/          # @ConfigurationProperties (TradingConfig, RevolutApiConfig)
├── model/
│   ├── entity/      # JPA entities (Trade, Position, Candlestick, SignalLog)
│   ├── dto/         # DTOs (OrderRequest, OrderResult, TradingStats, PortfolioSnapshot)
│   └── enums/       # SignalType, OrderSide, OrderStatus, TradingPair, TradingMode
├── market/          # MarketDataClient (API + signing), MarketDataService (BarSeries)
├── strategy/        # Strategy interface, Signal record, SignalEngine, impl/
├── risk/            # RiskManager, TakeProfitStopLossManager
├── execution/       # OrderExecutionService (router), PaperTradingService, LiveTradingService
├── portfolio/       # PortfolioService, TradeService
├── repository/      # Spring Data JPA repositories
├── scheduler/       # TradingLoop (@Scheduled orchestrator)
├── controller/      # DashboardController (REST monitoring API)
├── alert/           # AlertService (SLF4J for v1, Telegram later)
└── exception/       # Custom exceptions
```

### Database Schema

PostgreSQL, schema: `trading`. All monetary fields: `DECIMAL(18,8)`. Managed by Flyway.

**Tables:**
1. **positions** — id, pair, side, entry_price, quantity, take_profit, stop_loss, status (OPEN/CLOSED), opened_at, closed_at, signal_reason, created_at, updated_at
2. **trades** — id, position_id (FK), pair, side, entry_price, exit_price, quantity, pnl, pnl_pct, exit_reason (TP_HIT/SL_HIT/SIGNAL_EXIT/MANUAL), executed_at, trading_mode (PAPER/LIVE)
3. **candlesticks** — id, pair, interval, open/high/low/close_price, volume, timestamp. Unique constraint on (pair, interval, timestamp)
4. **signal_logs** — id, pair, signal_type, confidence, reason, ema_short, ema_long, rsi, current_price, created_at

### Trading Configuration (application.yml)

```yaml
trading:
  mode: PAPER
  pair: BTC-EUR
  poll-interval-seconds: 30
  candlestick-interval: 15m
  strategy:
    short-ema-period: 9
    long-ema-period: 21
    rsi-period: 14
    rsi-overbought: 70
    rsi-oversold: 30
  risk:
    take-profit-pct: 5.0
    stop-loss-pct: 3.0
    max-position-pct: 2.0
    max-concurrent-positions: 3
    max-daily-loss-pct: 5.0
    consecutive-loss-limit: 5
```

---

## V1 Strategy — EMA Crossover + RSI Filter

Using Ta4j library with configurable parameters.

**BUY signal (all conditions must be true):**
- EMA(9) crosses above EMA(21)
- RSI(14) between 30 and 70 (not overbought/oversold)
- Current price above EMA(21) (confirms uptrend)

**SELL signal (any condition):**
- EMA(9) crosses below EMA(21) (momentum reversal)
- RSI(14) > 70 (overbought)

**HOLD:** Everything else.

### Risk Management Rules
- Max 2% of portfolio per trade
- Max 3 concurrent open positions
- Circuit breaker: stop trading if daily loss exceeds 5%
- Circuit breaker: stop trading after 5 consecutive losses
- Take Profit: entry_price × 1.05 (5%)
- Stop Loss: entry_price × 0.97 (3%)

### Trading Loop (every 30s via @Scheduled)
1. `MarketDataService.fetchLatestCandles("BTC-EUR", "15m")`
2. Build/update Ta4j `BarSeries`
3. `SignalEngine.evaluate("BTC-EUR")` → BUY/SELL/HOLD
4. If BUY/SELL → `RiskManager.validateTrade(signal)` → position sizing → execute
5. Monitor all open positions: check current price against TP/SL
6. Close positions that hit TP/SL, record trades
7. Log everything via `AlertService`

---

## Build Phases

### Phase 1 — Foundation ✅
- application.yml with all config
- Flyway migration V1__init_schema.sql (4 tables + indexes)
- Enums: SignalType, OrderSide, OrderStatus, TradingPair, TradingMode
- JPA Entities: Position, Trade, Candlestick, SignalLog
- Spring Data JPA Repositories with custom queries
- Config classes: TradingConfig, RevolutApiConfig as @ConfigurationProperties
- **Milestone:** App boots, Flyway runs, tables created

### Phase 2 — API Client & Auth ✅
- RevolutApiSigner — Ed25519 message signing (load PEM, construct message, sign, base64 encode)
- MarketDataClient — OkHttp-based client with signing interceptor
- Handles all Revolut X endpoints (public + authenticated)
- Rate limiting awareness
- **Milestone:** Can fetch live BTC-EUR data from Revolut X

### Phase 3 — Signal Engine ✅
- MarketDataService — fetches candles (`/candles/{symbol}?interval=15`), builds Ta4j BarSeries, persists to DB
- `TradingStrategy` interface: `Signal evaluate(BarSeries series)`
- `EmaCrossoverStrategy` — implements EMA(9/21) + RSI(14) logic using Ta4j indicators
- `Signal` record: type, confidence, reason, pair, evaluatedAt (Instant), emaShort, emaLong, rsi, currentPrice
- `SignalEngine` — orchestrates fetch → evaluate → persist to signal_logs. Two modes: `evaluateAndPersist()` (API call) and `evaluateFromCache()` (no API call)
- Test endpoints: `GET /test/signals/current`, `/test/signals/cached`, `/test/signals/history`
- **Milestone:** Live EMA/RSI signals computed from real BTC-EUR candles ✅

### Phase 4 — Risk Management & Paper Trading ✅
- `RiskValidationResult` — record: approved, reason, positionSizeEur
- `RiskManager` — validates concurrent positions, daily loss CB, consecutive loss CB; calculates 2% position size. `currentStatus()` for monitoring.
- `TakeProfitStopLossManager` — calculates TP/SL prices; `checkExitCondition()` returns Optional<exitReason>
- `PaperTradingService` — `openPosition()` creates Position + Trade; `closePosition()` fills exitPrice/PnL and marks CLOSED
- `OrderExecutionService` — `executeSignal()` opens/closes on BUY/SELL; `monitorPositions()` checks TP/SL on all open positions
- Test endpoints: `GET /test/risk/status`, `GET /test/risk/validate`, `POST /test/paper/simulate`, `GET /test/paper/positions`, `GET /test/paper/trades`
- **Milestone:** Full paper trade execution from signal → risk check → simulated fill ✅

### Phase 5 — Trading Loop & Portfolio ✅
- `TradingLoop` — `@Scheduled(fixedDelay)` every 30s. Cycle: evaluate signal → monitor TP/SL → fetch balance → log risk state → execute signal → log portfolio snapshot. All exceptions caught — a bad cycle never stops the bot.
- `PortfolioService` — `getSnapshot(currentPrice)` returns `PortfolioSnapshot`: openPositions, totalInvested, unrealisedPnl, unrealisedPnlPct
- `TradeService` — `getStats()` returns `TradingStats`: winRate, totalPnl, averageWin/Loss, bestTrade, worstTrade, expectancy
- `PortfolioSnapshot` + `TradingStats` — immutable record DTOs
- Test endpoints: `GET /test/portfolio/snapshot`, `GET /test/portfolio/stats`
- **Milestone:** Bot runs autonomously in paper mode, logs every cycle ✅

### Phase 6 — Monitoring Dashboard ✅
- `BotStateService` — `AtomicBoolean` running flag; `stop()` / `resume()` thread-safe
- `AlertService` — structured SLF4J events: `[TRADE OPEN]`, `[TRADE CLOSE]`, `[CIRCUIT BREAKER]`, `[BOT STOP/RESUME]`. V2: swap in Telegram client.
- `TradingLoop` — checks `BotStateService.isActive()` before every cycle; wires `AlertService` on circuit breaker
- `PaperTradingService` — calls `alertService.positionOpened/Closed()` on every trade
- `TradeService.getPnlBreakdown()` — returns daily/weekly/monthly/all-time PnL using `sumPnlSince()`
- `DashboardController` (`GET/POST /api/*`):
    - `GET /api/status` — running, mode, pair, openPositions, dailyPnl, consecutiveLosses, circuitBreakerOn
    - `GET /api/positions` — open positions with live unrealised PnL at current market price
    - `GET /api/trades?limit=50` — recent closed trades
    - `GET /api/stats` — win rate, total PnL, avgWin/Loss, expectancy
    - `GET /api/pnl` — PnL broken down by day/week/month/all-time
    - `POST /api/emergency-stop` — stops bot immediately (open positions stay open)
    - `POST /api/resume` — resumes bot after emergency stop
    - `POST /api/config` — runtime config update (risk params, strategy params, paper balance); changes take effect next cycle
- **Milestone:** Can monitor and control bot remotely via REST ✅

### Phase 7 — Multi-Strategy Parallel Paper Trading

**Goal:** Run N strategies simultaneously, each with its own independent virtual portfolio. Same market data, same time window — but every strategy makes its own decisions, opens its own positions, and tracks its own P&L. After weeks of data you compare them side-by-side.

---

#### Core idea: everything is strategy-scoped

Every position, trade, and signal log is tagged with `strategy_name`. The risk manager, portfolio service, and trade service all accept a `strategyName` filter. Nothing is global anymore.

---

#### Step 1 — Flyway migration `V2__add_strategy_name.sql`

```sql
ALTER TABLE trading.signal_logs
    ADD COLUMN strategy_name VARCHAR(50) NOT NULL DEFAULT 'EMA_CROSSOVER';

ALTER TABLE trading.positions
    ADD COLUMN strategy_name VARCHAR(50) NOT NULL DEFAULT 'EMA_CROSSOVER';

ALTER TABLE trading.trades
    ADD COLUMN strategy_name VARCHAR(50) NOT NULL DEFAULT 'EMA_CROSSOVER';
```

---

#### Step 2 — Update JPA entities

Add to `SignalLog`, `Position`, and `Trade`:
```java
@Column(name = "strategy_name", nullable = false, length = 50)
private String strategyName;
```

---

#### Step 3 — Update `TradingStrategy` interface

```java
public interface TradingStrategy {
    Signal evaluate(BarSeries series);
    String name();   // "EMA_CROSSOVER", "MACD", "BOLLINGER", "RSI_MOMENTUM"
}
```

`EmaCrossoverStrategy.name()` returns `"EMA_CROSSOVER"`. Update its `@Component` to include the name.

---

#### Step 4 — New strategies (all in `strategy/impl/`)

All strategies receive `TradingConfig` for the pair name. Use the existing `Signal` record unchanged — repurpose fields where needed (documented below).

**`MacdStrategy` — name: `"MACD"`**
- `MACDIndicator(close, 12, 26)` → MACD line
- `EMAIndicator(macd, 9)` → signal line
- histogram = MACD line − signal line
- BUY: histogram crosses above zero (prev ≤ 0, current > 0)
- SELL: histogram crosses below zero (prev ≥ 0, current < 0)
- HOLD: else
- Signal fields: `emaShort` = MACD line value, `emaLong` = signal line value, `rsi` = histogram

**`BollingerBandsStrategy` — name: `"BOLLINGER"`**
- `SMAIndicator(close, 20)` → middle band
- `StandardDeviationIndicator(close, 20)` → σ
- upper = SMA + (2 × σ), lower = SMA − (2 × σ)
- BUY: price crosses above lower band (prev bar price ≤ lower, current > lower)
- SELL: price crosses above upper band (prev bar price ≤ upper, current > upper)
- HOLD: price inside bands
- Signal fields: `emaShort` = upper band, `emaLong` = lower band, `rsi` = %B = (price − lower) / (upper − lower) × 100

**`RsiMomentumStrategy` — name: `"RSI_MOMENTUM"`**
- `RSIIndicator(close, 14)`
- BUY: RSI crosses above 30 (prev < 30, current ≥ 30) — recovering from oversold
- SELL: RSI crosses above 70 (prev < 70, current ≥ 70) — entering overbought
- HOLD: else
- Signal fields: `emaShort` = null, `emaLong` = null, `rsi` = current RSI

---

#### Step 5 — Update `SignalEngine`

Inject `List<TradingStrategy>` — Spring autowires all implementations automatically.

```java
// Fetch candles ONCE — shared across all strategies
BarSeries series = marketDataService.fetchAndBuildBarSeries();

// Run every strategy, persist every signal
List<Signal> allSignals = strategies.stream()
    .map(s -> {
        Signal signal = s.evaluate(series);
        persist(signal, s.name());   // persist now takes strategyName
        return signal;
    }).toList();

// Return signal from the primary strategy for the trading loop
return allSignals.stream()
    .filter(s -> s.strategyName().equals(config.getPrimaryStrategy()))
    .findFirst()
    .orElseThrow();
```

`Signal` record: add `String strategyName` field so the signal carries its own identity through the pipeline.

`persist(signal, strategyName)` sets `strategyName` on `SignalLog`.

---

#### Step 6 — Update `TradingLoop`

Instead of one execution cycle, iterate all strategies:

```java
// Fetch once — returns signals for ALL strategies
List<Signal> signals = signalEngine.evaluateAllAndPersist();
BigDecimal currentPrice = signals.get(0).currentPrice();

// Each strategy monitors and executes its own positions independently
for (Signal signal : signals) {
    orderExecutionService.monitorPositions(currentPrice, signal.strategyName());
    BigDecimal balance = resolveBalance(signal.strategyName());
    orderExecutionService.executeSignal(signal, balance, currentPrice);
}
```

---

#### Step 7 — Update `OrderExecutionService`

`monitorPositions(price, strategyName)` — only checks positions tagged with that strategy.
`executeSignal(signal, balance, price)` — passes `signal.strategyName()` down to `PaperTradingService`.

---

#### Step 8 — Update `PaperTradingService`

`openPosition(signal, sizeEur, price)` — sets `strategyName` on both `Position` and `Trade` from `signal.strategyName()`.
`closePosition(position, price, reason)` — already works; position carries the strategy name.

---

#### Step 9 — Update `RiskManager`

All queries become strategy-scoped:

```java
// concurrent positions: count only this strategy's open positions
positionRepository.countByStatusAndStrategyName(OPEN, strategyName)

// daily PnL: sum only this strategy's trades today
tradeRepository.sumPnlSinceAndStrategyName(startOfDay, strategyName)

// consecutive losses: last N trades for this strategy
tradeRepository.findRecentTradesByPairAndStrategy(pair, strategyName, limit)
```

Each strategy has its own independent circuit breakers.

---

#### Step 10 — Per-strategy paper balance in config

```yaml
trading:
  primary-strategy: EMA_CROSSOVER
  strategy-balances:
    EMA_CROSSOVER: 10000.00
    MACD: 10000.00
    BOLLINGER: 10000.00
    RSI_MOMENTUM: 10000.00
```

`TradingConfig` addition:
```java
private String primaryStrategy;
private Map<String, BigDecimal> strategyBalances;
```

`resolveBalance(strategyName)` in `TradingLoop` looks up `config.getStrategyBalances().get(strategyName)`.

---

#### Step 11 — Repository additions

**`PositionRepository`:**
```java
long countByStatusAndStrategyName(OrderStatus status, String strategyName);
List<Position> findByStatusAndStrategyName(OrderStatus status, String strategyName);
```

**`TradeRepository`:**
```java
@Query("SELECT COALESCE(SUM(t.pnl), 0) FROM Trade t WHERE t.executedAt >= :since AND t.strategyName = :strategyName")
BigDecimal sumPnlSinceAndStrategyName(@Param("since") LocalDateTime since, @Param("strategyName") String strategyName);

@Query("SELECT t FROM Trade t WHERE t.pair = :pair AND t.strategyName = :strategyName ORDER BY t.executedAt DESC LIMIT :limit")
List<Trade> findRecentTradesByPairAndStrategy(@Param("pair") String pair, @Param("strategyName") String strategyName, @Param("limit") int limit);

List<Trade> findByStrategyNameOrderByExecutedAtDesc(String strategyName);
```

**`SignalLogRepository`:**
```java
List<SignalLog> findByPairAndStrategyNameOrderByCreatedAtDesc(String pair, String strategyName);

@Query("SELECT s.strategyName, s.signalType, COUNT(s) FROM SignalLog s WHERE s.pair = :pair GROUP BY s.strategyName, s.signalType")
List<Object[]> countSignalsByStrategy(@Param("pair") String pair);
```

---

#### Step 12 — Dashboard API additions (`DashboardController`)

```
GET /api/strategies                         — list all registered strategy names + their stats
GET /api/strategies/{name}/positions        — open positions for one strategy with live PnL
GET /api/strategies/{name}/trades?limit=50  — closed trades for one strategy
GET /api/strategies/{name}/stats            — win rate, PnL, expectancy for one strategy
GET /api/strategies/{name}/pnl             — daily/weekly/monthly PnL for one strategy
GET /api/signals/summary                    — signal counts per strategy (BUY/SELL/HOLD breakdown)
```

---

#### Step 13 — Test endpoints to add to `TestController`

```
GET /test/signals/by-strategy?strategy=MACD&limit=20
GET /test/signals/strategy-summary
GET /test/strategies/{name}/snapshot        — portfolio snapshot for one strategy
```

---

#### What `Signal` record looks like after this phase

```java
public record Signal(
    SignalType type,
    BigDecimal confidence,
    String reason,
    String pair,
    String strategyName,     // NEW
    Instant evaluatedAt,
    BigDecimal emaShort,
    BigDecimal emaLong,
    BigDecimal rsi,
    BigDecimal currentPrice
) {}
```

---

#### Milestone
Four strategies run every 30s on the same candle data. Each has its own positions, trades, circuit breakers, and P&L. Query `trades` grouped by `strategy_name` to see who's winning.

---

### Phase 8 — Additional Strategies (Tier 1 + Tier 2)

**Goal:** Add 5 more strategies to the parallel engine. All plug into the existing architecture — implement `TradingStrategy`, add to `StrategyType`, done. No migrations needed (strategy_name column already exists).

---

#### Step 1 — Add to `StrategyType` enum

```java
EMA_CROSSOVER("EMA Crossover"),
MACD("MACD"),
BOLLINGER("Bollinger Bands"),
RSI_MOMENTUM("RSI Momentum"),
// Tier 1 — add now
STOCH_RSI("Stochastic RSI"),
TRIPLE_EMA("Triple EMA"),
PARABOLIC_SAR("Parabolic SAR"),
// Tier 2 — add now too, implement same session
ADX_DI("ADX + Directional Index"),
CCI("CCI");
```

---

#### Step 2 — Add per-strategy balances to `application.yml`

```yaml
trading:
  strategy-balances:
    # existing
    EMA_CROSSOVER: 10000.00
    MACD: 10000.00
    BOLLINGER: 10000.00
    RSI_MOMENTUM: 10000.00
    # new
    STOCH_RSI: 10000.00
    TRIPLE_EMA: 10000.00
    PARABOLIC_SAR: 10000.00
    ADX_DI: 10000.00
    CCI: 10000.00
```

---

#### Step 3 — Implement strategies (all in `strategy/impl/`)

All strategies follow the same pattern as existing ones: `@Component`, `@RequiredArgsConstructor`, implement `TradingStrategy`. The `Signal` record's `emaShort`/`emaLong`/`rsi` fields are repurposed to carry the most useful indicator values for each strategy — documented below so the frontend knows what to display.

---

**`StochRsiStrategy` — name: `STOCH_RSI`**

RSI applied to RSI — faster and more sensitive than plain RSI. Catches short-term reversals.

```java
// Ta4j classes:
RSIIndicator rsi = new RSIIndicator(close, 14);
StochasticRSIIndicator stochRsi = new StochasticRSIIndicator(rsi, 14);
// stochRsi value is 0.0–1.0 — multiply by 100 for display
```

- BUY: stochRsi crosses above 0.20 (prev < 0.20, current ≥ 0.20)
- SELL: stochRsi crosses above 0.80 (prev < 0.80, current ≥ 0.80)
- HOLD: else

Signal field mapping:
- `emaShort` = stochRsi value × 100 (0–100 scale for display)
- `emaLong` = null
- `rsi` = underlying RSI value

---

**`TripleEmaStrategy` — name: `TRIPLE_EMA`**

Three EMAs must all align in the same direction — filters out the false crossovers the 2-EMA strategy suffers from in choppy markets.

```java
EMAIndicator ema5  = new EMAIndicator(close, 5);
EMAIndicator ema13 = new EMAIndicator(close, 13);
EMAIndicator ema34 = new EMAIndicator(close, 34);
```

- BUY: ema5 > ema13 > ema34 (full bullish stack)
- SELL: ema5 < ema13 < ema34 (full bearish stack)
- HOLD: mixed (sideways — no trade)

Signal field mapping:
- `emaShort` = EMA5 value
- `emaLong` = EMA34 value
- `rsi` = EMA13 value (middle — repurposed)

---

**`ParabolicSarStrategy` — name: `PARABOLIC_SAR`**

A trailing dot that flips sides when trend reverses. Completely different signal shape from all existing strategies — clean flips, no oscillators involved.

```java
// Ta4j class:
ParabolicSarIndicator sar = new ParabolicSarIndicator(series, new DecimalNum(0.02), new DecimalNum(0.2));
// 0.02 = acceleration factor start, 0.2 = max acceleration
```

- BUY: price crosses above SAR (prev close ≤ prev SAR, current close > current SAR)
- SELL: price crosses below SAR (prev close ≥ prev SAR, current close < current SAR)
- HOLD: no crossover this bar

Signal field mapping:
- `emaShort` = current SAR value
- `emaLong` = null
- `rsi` = distance between price and SAR as % ((price - SAR) / price × 100)

---

**`AdxDiStrategy` — name: `ADX_DI`**

The only strategy that measures **trend strength** rather than direction. Refuses to trade in choppy, sideways markets — the condition where all other strategies get whipsawed.

```java
ADXIndicator adx       = new ADXIndicator(series, 14);
PlusDIIndicator plusDi  = new PlusDIIndicator(series, 14);
MinusDIIndicator minusDi = new MinusDIIndicator(series, 14);
```

- BUY: +DI crosses above -DI AND ADX > 25 (strong uptrend confirmed)
- SELL: -DI crosses above +DI AND ADX > 25 (strong downtrend confirmed)
- HOLD: ADX < 20 OR no DI crossover (no clear trend — sit out)

Signal field mapping:
- `emaShort` = +DI value
- `emaLong` = -DI value
- `rsi` = ADX value (key number — show prominently in UI)

---

**`CciStrategy` — name: `CCI`**

Commodity Channel Index — measures deviation from a statistical mean. Different oscillator from RSI; better at identifying cyclical turning points.

```java
CCIIndicator cci = new CCIIndicator(series, 20);
// Typical range: -200 to +200. Overbought > +100, oversold < -100
```

- BUY: CCI crosses above −100 (prev < −100, current ≥ −100) — recovering from oversold
- SELL: CCI crosses above +100 (prev < +100, current ≥ +100) — entering overbought
- HOLD: else

Signal field mapping:
- `emaShort` = null
- `emaLong` = null
- `rsi` = CCI value (can be outside 0–100 — display as-is)

---

#### Step 4 — No other changes needed

Spring autowires all `TradingStrategy` beans automatically via `List<TradingStrategy>` in `SignalEngine`. Adding `@Component` to each new class is enough — they join the parallel execution loop on the next restart.

Verify with `GET /api/strategies` — all 9 strategy names should appear.

---

#### Milestone
9 strategies run every 30 seconds on shared candle data. Each has independent positions, circuit breakers, and P&L. The dashboard shows 9 tabs. After several weeks, compare expectancy across all 9 to identify which to keep for multi-pair rollout.

---

### Phase 9 — Multi-Pair Support

**Goal:** Run every strategy on multiple crypto pairs simultaneously. The unit of execution becomes `(pair, strategy)` — e.g. 4 strategies × 3 pairs = 12 independent virtual portfolios, all running in parallel on the same 30-second heartbeat.

**What already works without changes:**
- `Position`, `Trade`, `SignalLog` entities — already have a `pair` column ✅
- All repository queries — already filter by pair ✅
- Strategy implementations — receive a `BarSeries`, don't care which pair it came from ✅
- `PaperTradingService` — already writes pair from the signal ✅

---

#### Step 1 — Config change: single pair → list of pairs

`application.yml`:
```yaml
trading:
  pairs: [BTC-EUR, ETH-EUR, SOL-EUR]   # replaces single `pair:` field
  mode: PAPER
  polling-interval-seconds: 30
  primary-strategy: EMA_CROSSOVER
  strategy-balances:
    BTC-EUR:
      EMA_CROSSOVER: 10000.00
      MACD: 10000.00
      BOLLINGER: 10000.00
      RSI_MOMENTUM: 10000.00
    ETH-EUR:
      EMA_CROSSOVER: 5000.00
      MACD: 5000.00
      BOLLINGER: 5000.00
      RSI_MOMENTUM: 5000.00
    SOL-EUR:
      EMA_CROSSOVER: 5000.00
      MACD: 5000.00
      BOLLINGER: 5000.00
      RSI_MOMENTUM: 5000.00
```

`TradingConfig` changes:
```java
// Remove:
private String pair;

// Add:
@NotEmpty
private List<String> pairs;                              // all active pairs

private Map<String, Map<StrategyType, BigDecimal>> strategyBalances;  // pair → strategy → balance

// Helper — used anywhere that previously read config.getPair()
public String primaryPair() { return pairs.get(0); }
```

---

#### Step 2 — `MarketDataService`: one BarSeries per pair

```java
// Before: single BarSeries field
private BarSeries barSeries;

// After: map keyed by pair
private final Map<String, BarSeries> barSeriesMap = new ConcurrentHashMap<>();

// Fetch candles for one specific pair, update its BarSeries
public BarSeries fetchBarSeriesForPair(String pair) { ... }

// Get cached series (no API call)
public BarSeries getBarSeriesForPair(String pair) { ... }

// Get current price for a specific pair (from ticker)
public BigDecimal getCurrentPriceForPair(String pair) { ... }
```

---

#### Step 3 — `SignalEngine`: evaluate all pairs × all strategies

```java
// New method — replaces evaluateAllAndPersist()
public List<Signal> evaluateAllPairsAndPersist() {
    // Fetch candles for ALL pairs in parallel (CompletableFuture)
    Map<String, BarSeries> seriesMap = config.getPairs().parallelStream()
        .collect(toMap(pair -> pair, marketDataService::fetchBarSeriesForPair));

    // For each pair, run all strategies
    return config.getPairs().stream()
        .flatMap(pair -> strategies.stream()
            .map(strategy -> {
                Signal signal = strategy.evaluate(seriesMap.get(pair), pair);
                persist(signal);
                return signal;
            }))
        .toList();
    // Returns N_pairs × N_strategies signals e.g. 12 signals for 3 pairs × 4 strategies
}
```

`TradingStrategy.evaluate()` gains a `pair` parameter:
```java
Signal evaluate(BarSeries series, String pair);
```

The `pair` is used when constructing the returned `Signal` record (already has a `pair` field).

---

#### Step 4 — `TradingLoop`: outer loop over pairs, inner loop over strategies

```java
private void runCycle() {
    List<Signal> allSignals = signalEngine.evaluateAllPairsAndPersist();

    // Group by pair so we log pair-level summaries cleanly
    Map<String, List<Signal>> byPair = allSignals.stream()
        .collect(groupingBy(Signal::pair));

    byPair.forEach((pair, signals) -> {
        BigDecimal currentPrice = marketDataService.getCurrentPriceForPair(pair);
        signals.forEach(signal -> runStrategyExecution(signal, currentPrice));
    });
}
```

`resolveBalance(signal)` now looks up `config.getStrategyBalances().get(signal.pair()).get(signal.strategyType())`.

---

#### Step 5 — `RiskManager`: queries already pair-scoped, just pass pair through

All existing queries (`countByStatusAndStrategyName`, `sumPnlSinceAndStrategyName`, `findRecentTradesByPairAndStrategy`) already accept a `pair` parameter. The only change is ensuring the `pair` from the signal flows into every risk check call.

---

#### Step 6 — `DashboardController`: add `?pair=` filter to all multi-strategy endpoints

```
GET /api/strategies?pair=BTC-EUR                         — strategies for one pair
GET /api/strategies/{name}/positions?pair=BTC-EUR        — positions for pair+strategy
GET /api/strategies/{name}/trades?pair=BTC-EUR&limit=50
GET /api/strategies/{name}/stats?pair=BTC-EUR
GET /api/strategies/{name}/pnl?pair=BTC-EUR
GET /api/signals/summary?pair=BTC-EUR                    — signal counts for one pair
GET /api/pairs                                           — NEW: list of configured pairs
```

`GET /api/pairs` — new endpoint, returns configured pairs and their display info:
```json
[
  { "pair": "BTC-EUR", "baseAsset": "BTC", "quoteAsset": "EUR" },
  { "pair": "ETH-EUR", "baseAsset": "ETH", "quoteAsset": "EUR" },
  { "pair": "SOL-EUR", "baseAsset": "SOL", "quoteAsset": "EUR" }
]
```

When `?pair=` is omitted, endpoints return data across ALL pairs (existing behaviour).

---

#### Step 7 — Flyway migration `V3__add_pair_index.sql`

No schema changes needed — `pair` column already exists on all tables. Just add a composite index to keep queries fast as data grows:

```sql
CREATE INDEX IF NOT EXISTS idx_positions_pair_strategy
    ON trading.positions (pair, strategy_name, status);

CREATE INDEX IF NOT EXISTS idx_trades_pair_strategy
    ON trading.trades (pair, strategy_name, executed_at DESC);

CREATE INDEX IF NOT EXISTS idx_signal_logs_pair_strategy
    ON trading.signal_logs (pair, strategy_name, created_at DESC);
```

---

#### Milestone
12 virtual portfolios (4 strategies × 3 pairs) run every 30 seconds. Each has isolated circuit breakers, its own paper balance, and independent P&L tracking. The dashboard shows a pair selector — pick BTC-EUR, ETH-EUR, or SOL-EUR to view that pair's strategy comparison.

---

### FINAL Phase — Live Trading

**Prerequisites before enabling LIVE mode:**
- All 4 strategies have run in paper mode for at least 2–4 weeks across all pairs
- At least one strategy shows consistent positive expectancy (>€0 per trade)
- Win rate is stable (not improving just from luck — check sample size)
- You understand the worst drawdown and are comfortable with it in real money terms

**What needs to be built:**
- `LiveTradingService` — real Revolut X order placement via `POST /orders`
  - Market order on BUY: `{ "order_configuration": { "market": { "quote_size": "100.00" } } }`
  - Market order on SELL: closes position at market price
  - Stores `venue_order_id` on `Position` for order tracking
- `GET /orders/{venue_order_id}` polling — confirm fill before recording position as OPEN
- TP/SL as real Revolut limit orders (so positions close even if app is offline — gap risk fix)
- `trading.mode: LIVE` switch in application.yml — `OrderExecutionService` routes to `LiveTradingService`
- Start with absolute minimum position sizes (€10–25 per trade)
- Paper and Live can run on different pairs simultaneously during transition

**Safety rules that must hold in LIVE mode:**
- NEVER place a real order without logging the full request first
- NEVER bypass the risk manager — `validate()` must return approved before any order
- NEVER risk more than `max-position-pct` per trade
- Circuit breakers apply to LIVE positions too — no exceptions
- **Milestone:** Real trades with real money, backed by weeks of validated paper performance

---

## Code Style

- **Lombok**: @Data, @Builder, @NoArgsConstructor, @AllArgsConstructor on entities. @Slf4j everywhere.
- **Records**: Use Java records for DTOs, value objects, and Signals where appropriate.
- **Methods**: Under 15 lines, single responsibility.
- **Money**: Always BigDecimal, never double/float.
- **Logging**: Comprehensive SLF4J logging. Log every signal, trade decision, API call, and error.
- **Repositories**: Use Optional returns where applicable.
- **Validation**: @NotNull, @Positive, etc. on config classes.
- **Testing**: Spock/Groovy framework. Write tests for strategy logic and risk manager.
- **Patterns**: Strategy pattern for trading strategies. Factory pattern where applicable. Keep services focused and composable.
- **Error handling**: Custom exceptions (InsufficientBalanceException, ApiRateLimitException, CircuitBreakerTrippedException). Never swallow exceptions silently.
- **Naming**: Follow existing Spring Boot conventions. Repositories end in Repository, services in Service, etc.

## Important Safety Rules

- **NEVER place real orders unless trading.mode is explicitly set to LIVE**
- **NEVER store API keys or private keys in code or yml** — always environment variables
- **NEVER risk more than max-position-pct per trade**
- **ALWAYS respect the circuit breaker** — if tripped, no trades until manually reset
- **ALWAYS log before executing** — every order attempt must be logged before API call
