# Revolut X Trading Bot — Project Blueprint

> **See also:** Root [`/CLAUDE.md`](../CLAUDE.md) for project-wide context (execution unit, how to run, API overview).
> Frontend-specific instructions are in [`revolut-trading-bot-ui/CLAUDE.md`](../revolut-trading-bot-ui/CLAUDE.md).
> **HTTP contract with the frontend:** [`../API_CONTRACT.md`](../API_CONTRACT.md) is the single source of truth for all `/api/**` endpoints, DTOs, and enums. Whenever you add/remove/rename a controller method or modify a DTO field, update that file in the same commit.

## Overview

Automated cryptocurrency trading bot in Java/Spring Boot that uses the Revolut X REST API. Starts in PAPER mode (simulated trades), validates the strategy, then optionally switches to LIVE mode with real money.

The fundamental execution unit is `(pair, strategy, interval)` — each combination is a fully isolated virtual portfolio with independent positions, trades, circuit breakers, and P&L tracking.

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
| GET | `/candles/{symbol}` | `interval` (int, minutes: 1/5/15/30/60/240/1440/10080…), `since` (epoch ms), `until` (epoch ms) | Historical OHLCV candles. Response field is `start` (not `timestamp`). Default returns last 5000 candles. |
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
1. **positions** — id, pair, **interval**, side, entry_price, quantity, take_profit, stop_loss, status (OPEN/CLOSED), strategy_name, opened_at, closed_at, signal_reason
2. **trades** — id, position_id (FK), pair, **interval**, side, entry_price, exit_price, quantity, pnl, pnl_pct, strategy_name, exit_reason (TP_HIT/SL_HIT/SIGNAL_EXIT/MANUAL), executed_at, closed_at, trading_mode (PAPER/LIVE)
3. **candlesticks** — id, pair, interval, open/high/low/close_price, volume, timestamp. Unique constraint on (pair, interval, timestamp)
4. **signal_logs** — id, pair, **interval**, strategy_name, signal_type, confidence, reason, ema_short, ema_long, rsi, current_price, created_at

All queries on positions, trades, and signal_logs filter by the triple `(pair, interval, strategy_name)`. The `interval` column stores labels like `"15m"`, `"1h"`, `"4h"`.

### Trading Configuration (application.yml)

```yaml
trading:
  pairs: [BTC-EUR, ETH-EUR, SOL-EUR]
  intervals: [15, 60, 240, 1440, 10080] # candle intervals in minutes (15m, 1h, 4h, 1d, 1w)
  mode: PAPER
  polling-interval-seconds: 30
  paper-balance: 10000.00
  primary-strategy: EMA_CROSSOVER
  strategy-balances:                   # nested: pair -> strategy -> EUR balance
    BTC-EUR:
      EMA_CROSSOVER: 10000.00
      # ... (12 strategies per pair)
  strategy:
    ema-short-period: 9
    ema-long-period: 21
    rsi-period: 14
    rsi-overbought: 70
    rsi-oversold: 30
  risk:
    take-profit-pct: 5.0
    stop-loss-pct: 3.0
    max-position-pct: 2.0
    max-concurrent-positions: 1
    max-daily-loss-pct: 5.0
    max-consecutive-losses: 5
```

**Interval mapping:** `15` -> `"15m"`, `60` -> `"1h"`, `240` -> `"4h"`, `1440` -> `"1d"`, `10080` -> `"1w"`. Helper: `TradingConfig.intervalLabel(int minutes)`.

**Balance sharing:** Balances are keyed by `(pair, strategy)` and shared across intervals. A position on `(BTC-EUR, EMA_CROSSOVER, 15m)` and one on `(BTC-EUR, EMA_CROSSOVER, 1h)` draw from the same virtual balance. Positions, trades, risk checks, and P&L are fully isolated per interval.

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
1. `SignalEngine.evaluateAllPairsAndPersist()` — triple loop: **pairs x intervals x strategies**
   - For each pair, for each interval: fetch candles once via `MarketDataService.fetchBarSeriesForPairAndInterval(pair, intervalLabel)`
   - For each strategy: evaluate on the BarSeries, inject interval via `signal.withInterval(intervalLabel)`
2. Group signals by `(pair, interval)`
3. For each group: get current price, then for each strategy signal:
   - Monitor TP/SL for open positions (scoped to pair + interval + strategy)
   - Check risk manager constraints (scoped to pair + interval + strategy)
   - Execute signal (BUY/SELL/HOLD)
4. Log everything via `AlertService`

Total signals per cycle: `N_pairs x N_intervals x N_strategies` (e.g. 3 x 2 x 12 = 72)

---

## Build Phases


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

---

## Phase 10 — Volume & Breakout Strategies (Next Session)

**Goal:** Add 3 strategies that cover signal categories none of the existing 9 address:
- Volume-based signals (MFI) — existing 9 are all price-only
- Breakout signals (Donchian) — existing 9 are all oscillator/crossover
- Complex multi-condition trend (Ichimoku) — existing 9 are all single-indicator

**No migrations needed.** The `strategy_name` column already exists on all tables. Just implement `TradingStrategy`, add to `StrategyType`, add balances to `application.yml`, and Spring autowires them automatically.

---

### Step 1 — Add to `StrategyType` enum

```java
// Existing 9:
EMA_CROSSOVER, MACD, BOLLINGER, RSI_MOMENTUM, STOCH_RSI, TRIPLE_EMA, PARABOLIC_SAR, ADX_DI, CCI

// Add these 3:
MFI("Money Flow Index"),
DONCHIAN("Donchian Breakout"),
ICHIMOKU("Ichimoku Cloud");
```

---

### Step 2 — Add balances to `application.yml`

Under `trading.strategy-balances`, add to each pair (BTC-EUR gets 10000, ETH-EUR and SOL-EUR get 5000):

```yaml
MFI: 10000.00
DONCHIAN: 10000.00
ICHIMOKU: 10000.00
```

---

### Step 3 — Implement strategies (all in `strategy/impl/`)

Same pattern as all existing strategies: `@Slf4j`, `@Component`, implement `TradingStrategy`. The `evaluate(BarSeries series, String pair)` method returns a `Signal` record.

---

**`MfiStrategy` — name: `MFI`**

Money Flow Index — RSI weighted by volume. Detects when buying/selling pressure (via volume) diverges from price, catching reversals before they appear in price-only indicators. Unique because it's the only strategy that uses volume data.

```java
// Ta4j class:
MFIIndicator mfi = new MFIIndicator(series, 14);
// Range: 0–100. Overbought > 80, oversold < 20
```

- BUY: MFI crosses above 20 (prev < 20, current ≥ 20) — recovering from oversold with volume confirmation
- SELL: MFI crosses above 80 (prev < 80, current ≥ 80) — entering overbought with volume confirmation
- HOLD: else

Minimum bars needed: 14 + 1 = 15

Signal field mapping:
- `emaShort` = null
- `emaLong` = null
- `rsi` = current MFI value (0–100, display like RSI)

Confidence: BUY=75, SELL=71, HOLD=50

---

**`DonchianStrategy` — name: `DONCHIAN`**

Donchian Channel breakout — the original "turtle trader" strategy. Buys when price breaks above the highest high of the last N bars, sells when price breaks below the lowest low. Pure breakout, no oscillators, no lagging averages. Generates far fewer signals than oscillator strategies — each signal is a genuine new high/low.

```java
// Ta4j classes:
DonchianChannelUpperIndicator upper = new DonchianChannelUpperIndicator(series, 20);
DonchianChannelLowerIndicator lower = new DonchianChannelLowerIndicator(series, 20);
ClosePriceIndicator close = new ClosePriceIndicator(series);
// upper = highest high over last 20 bars
// lower = lowest low over last 20 bars
```

- BUY: current close > upper band of PREVIOUS bar (prev close ≤ prev upper, current close > current upper)
  - i.e. price just made a new 20-bar high → breakout to the upside
- SELL: current close < lower band of PREVIOUS bar (prev close ≥ prev lower, current close < current lower)
  - i.e. price just made a new 20-bar low → breakout to the downside
- HOLD: price inside the channel

Minimum bars needed: 20 + 1 = 21

Signal field mapping:
- `emaShort` = upper channel value
- `emaLong` = lower channel value
- `rsi` = channel width as % of price ((upper - lower) / close × 100) — shows how volatile/wide the channel is

Confidence: BUY=77, SELL=73, HOLD=50

---

**`IchimokuStrategy` — name: `ICHIMOKU`**

Ichimoku Cloud — Japanese system that generates the most conditions-rich signal of all strategies. Only fires when multiple components agree: the cloud must be bullish AND price must be above the cloud AND the lagging span must confirm. This is the most selective strategy — expect the fewest signals but highest quality.

```java
// Ta4j classes (all built-in):
IchimokuTenkanSenIndicator tenkan   = new IchimokuTenkanSenIndicator(series, 9);   // fast line
IchimokuKijunSenIndicator kijun     = new IchimokuKijunSenIndicator(series, 26);   // slow line
IchimokuSenkouSpanAIndicator spanA  = new IchimokuSenkouSpanAIndicator(series, tenkan, kijun);  // cloud top
IchimokuSenkouSpanBIndicator spanB  = new IchimokuSenkouSpanBIndicator(series, 52);             // cloud bottom
// Chikou (lagging span) = close price shifted back 26 bars = close.getValue(lastIdx - 26)
```

- BUY (all must be true):
  - Tenkan crosses above Kijun (prev tenkan ≤ prev kijun, current tenkan > kijun) — TK cross
  - Current close > max(spanA, spanB) — price is ABOVE the cloud
  - SpanA > SpanB — cloud is bullish (green cloud)
- SELL (all must be true):
  - Tenkan crosses below Kijun (prev tenkan ≥ prev kijun, current tenkan < kijun)
  - Current close < min(spanA, spanB) — price is BELOW the cloud
  - SpanB > SpanA — cloud is bearish (red cloud)
- HOLD: any condition unmet (price inside cloud = "in the fog", no trade)

Minimum bars needed: 52 + 26 + 1 = 79 bars (needs the most warmup of all strategies)

Signal field mapping:
- `emaShort` = Tenkan-sen value
- `emaLong` = Kijun-sen value
- `rsi` = SpanA value (cloud top — most useful display value)

Confidence: BUY=82, SELL=78, HOLD=50

---

### Step 4 — No other changes needed

Spring autowires all `TradingStrategy` beans via `List<TradingStrategy>` in `SignalEngine`. Adding `@Component` to each new class is enough.

Verify with `GET /api/strategies` — all 12 strategy names should appear after restart.

---

### Milestone

12 strategies across 3 pairs = 36 independent virtual portfolios. Volume (MFI), breakout (Donchian), and multi-condition trend (Ichimoku) now cover the signal categories missing from the original 9. After several more weeks of paper data, compare expectancy across all 12 to identify the strongest candidates for live trading.

---

### Phase 11 — Multi-Interval Support

**Goal:** Run all 12 strategies on multiple candle intervals simultaneously (15m, 1h, and potentially more). The execution unit changes from `(pair, strategy)` to `(pair, strategy, interval)`. Each triple is a fully isolated virtual portfolio. This enables direct performance comparison of the same strategy across different timeframes.

**Key design decisions:**
- Strategies are **interval-agnostic** — they receive a `BarSeries` and don't know which interval produced it. The `SignalEngine` injects the interval via `Signal.withInterval()` after evaluation. Zero changes to the 12 strategy implementation files.
- Balances are **shared** per `(pair, strategy)` across intervals. Positions, trades, risk, and PnL are **isolated** per `(pair, strategy, interval)`.
- The 30-second polling loop fetches candles for ALL intervals every cycle. For 1h candles, the API mostly returns the same still-forming candle — strategies evaluate HOLD. TP/SL monitoring still runs every 30s regardless of interval.

**What was changed:**

1. **Database migration V5** — added `interval VARCHAR(10) NOT NULL DEFAULT '15m'` to `positions`, `trades`, `signal_logs` with composite indexes
2. **TradingConfig** — added `intervals: [15, 60]` list, `primaryInterval()`, `intervalLabel(int)` helpers
3. **Signal record** — added `String interval` field + `withInterval()` copy method
4. **Position, Trade, SignalLog entities** — added `interval` column
5. **All repositories** — added interval-scoped query variants
6. **MarketDataService** — removed hardcoded 15m constants, parameterized with `fetchBarSeriesForPairAndInterval(pair, intervalLabel)`, composite cache key `"pair::interval"`
7. **SignalEngine** — triple loop: pairs x intervals x strategies
8. **TradingLoop** — groups signals by `(pair, interval)`
9. **RiskManager** — interval-scoped validation and circuit breakers
10. **Execution layer** — passes `signal.interval()` through to position/trade creation
11. **DashboardController** — added `?interval=` param to all endpoints, new `GET /api/intervals` endpoint
12. **All service classes** — gained `interval` parameter

**API changes:**
- All endpoints that accept `?pair=` now also accept `?interval=` (optional, defaults to primary interval)
- New endpoint: `GET /api/intervals` returns configured intervals

```
GET /api/intervals
[
  { "minutes": 15, "label": "15m", "displayName": "15 min" },
  { "minutes": 60, "label": "1h",  "displayName": "1 hour" }
]
```

**Adding a new interval:** Just add the minutes value to `trading.intervals` in `application.yml` and restart. No code changes needed. The system will start fetching candles, evaluating strategies, and tracking performance for the new interval immediately.

---

### Phase 12 — Crypto Sentiment Dashboard

**Goal:** Add a crypto-sentiment layer that blends the Crypto Fear & Greed Index with Reddit chatter to produce a numeric indicator visible on the dashboard. This first pass is **observation-only** — persist snapshots, expose API endpoints, show a UI panel, and log sentiment alongside trading signals for later correlation analysis. **No effect on the 12 live strategies, the `SignalEngine`, the `RiskManager`, or the execution layer.** Wiring into trading decisions is deferred to a future phase once the data is trusted.

**What already exists:**
- `service/FearGreedService.java` — fetches `https://api.alternative.me/fng/?limit=1` via Spring `RestClient` with a 1h in-memory cache. Not persisted. Not scheduled. Exposed via `GET /api/market/fear-greed`. Keep this service as-is; Phase 12 wraps it with a persistence + scheduling layer on top.
- `model/dto/FearGreedResponse.java` — `(int value, String classification, long timestamp)`. Reused.

**Design decisions (locked before implementation):**
- **Scope:** dashboard only. Zero changes to `StrategyType`, `SignalEngine`, `TradingLoop`, `RiskManager`, `OrderExecutionService`, or any `strategy/impl/*` file.
- **Sources:** Fear & Greed Index + Reddit. Twitter is excluded (no viable free API since 2023). RSS / raw-HTML scraping is excluded (brittle, ToS minefield, would need local NLP).
- **Reddit scoring:** chatter intensity only. No keyword polarity, no NLP, no LLM calls. The Reddit score measures *how loud Reddit is*, not sentiment direction.
- **Per-pair:** per-subreddit mapping where possible, with market-wide fallback. F&G is always market-wide.

---

#### Step 1 — Flyway migration V6

File: `src/main/resources/db/migration/V6__sentiment_snapshots.sql`

```sql
CREATE TABLE trading.sentiment_snapshots (
    id              BIGSERIAL PRIMARY KEY,
    source          VARCHAR(32)  NOT NULL,            -- FEAR_GREED | REDDIT
    pair            VARCHAR(20),                      -- NULL = market-wide
    score           SMALLINT     NOT NULL,            -- normalized 0..100
    classification  VARCHAR(32)  NOT NULL,            -- e.g. "Extreme Fear", "Quiet", "Heated"
    raw_payload     TEXT,                             -- original JSON for audit / later re-scoring
    fetched_at      TIMESTAMP    NOT NULL,
    CONSTRAINT chk_sentiment_score_range CHECK (score BETWEEN 0 AND 100)
);

CREATE INDEX idx_sentiment_source_fetched ON trading.sentiment_snapshots (source, fetched_at DESC);
CREATE INDEX idx_sentiment_pair_fetched   ON trading.sentiment_snapshots (pair, fetched_at DESC);
```

All scores are normalized to 0–100 so blending is arithmetic. Raw JSON is preserved so snapshots can be re-scored later (e.g. if LLM scoring is added) without re-fetching.

---

#### Step 2 — Package structure

All new code in a new `sentiment/` subpackage to contain blast radius. Entities and DTOs stay in existing packages for convention.

```
com.stefo.revolut_trading_bot/
├── sentiment/
│   ├── SentimentSource.java              # interface: List<SentimentReading> fetch()
│   ├── SentimentService.java             # orchestrates sources, reads latest, computes blended
│   ├── client/
│   │   ├── FearGreedSource.java          # wraps existing FearGreedService + persists
│   │   └── RedditSource.java             # reddit JSON API + chatter intensity scoring
│   ├── model/
│   │   ├── SentimentReading.java         # record: source, pair, score, classification, fetchedAt, rawJson
│   │   └── BlendedSentiment.java         # record: pair, blendedScore, bias, List<Component>
│   └── scheduler/
│       └── SentimentScheduler.java       # @Scheduled refresh jobs
├── config/
│   └── SentimentConfig.java              # @ConfigurationProperties("sentiment")
├── model/
│   ├── enums/
│   │   └── SentimentSourceType.java      # FEAR_GREED, REDDIT
│   ├── entity/
│   │   └── SentimentSnapshot.java        # JPA entity mapping the V6 table
│   └── dto/
│       ├── SentimentSnapshotDto.java
│       └── BlendedSentimentDto.java
├── repository/
│   └── SentimentSnapshotRepository.java
└── controller/
    └── SentimentController.java          # GET /api/sentiment/**
```

---

#### Step 3 — Enums, entity, repository

`SentimentSourceType`:
```java
public enum SentimentSourceType {
    FEAR_GREED("Fear & Greed"),
    REDDIT("Reddit");

    private final String displayName;
    // constructor + getter — same convention as StrategyType
}
```

`SentimentSnapshot` entity (JPA, `@Enumerated(EnumType.STRING)` on `source`, Lombok `@Data`/`@Builder`, `@Column(name = "raw_payload", columnDefinition = "TEXT")`).

`SentimentSnapshotRepository extends JpaRepository<SentimentSnapshot, Long>` with at minimum:
```java
Optional<SentimentSnapshot> findFirstBySourceAndPairOrderByFetchedAtDesc(SentimentSourceType source, String pair);
Optional<SentimentSnapshot> findFirstBySourceAndPairIsNullOrderByFetchedAtDesc(SentimentSourceType source);

List<SentimentSnapshot> findBySourceAndPairAndFetchedAtAfterOrderByFetchedAtAsc(
    SentimentSourceType source, String pair, LocalDateTime since);
List<SentimentSnapshot> findBySourceAndPairIsNullAndFetchedAtAfterOrderByFetchedAtAsc(
    SentimentSourceType source, LocalDateTime since);
```

---

#### Step 4 — `SentimentSource` interface + records

```java
public interface SentimentSource {
    SentimentSourceType sourceType();
    boolean isEnabled();
    List<SentimentReading> fetch();        // may return 0..N readings (multi-subreddit for Reddit)
}

public record SentimentReading(
    SentimentSourceType source,
    String pair,                  // null = market-wide
    int score,                    // 0..100
    String classification,
    String rawJson,
    Instant fetchedAt
) {}

public record BlendedSentiment(
    String pair,                  // null = market-wide
    int blendedScore,             // 0..100
    Bias bias,                    // EXTREME_FEAR | FEAR | NEUTRAL | GREED | EXTREME_GREED
    List<Component> components    // one per contributing source
) {
    public record Component(SentimentSourceType source, int score, String classification) {}
    public enum Bias { EXTREME_FEAR, FEAR, NEUTRAL, GREED, EXTREME_GREED }
}
```

---

#### Step 5 — `FearGreedSource`

- Injects the existing `FearGreedService` — calls `.get()` and converts the returned `FearGreedResponse` into a single market-wide `SentimentReading` (pair = null).
- Score is already 0–100; classification is already human-readable.
- `rawJson`: serialize the `FearGreedResponse` via the app's `ObjectMapper`.
- Persists one row per refresh.

---

#### Step 6 — `RedditSource` — chatter intensity

**HTTP:** use Spring `RestClient` (same pattern as `FearGreedService`). Reddit requires a distinctive `User-Agent` header — omitting it gets you 429s.

**For each configured subreddit, per refresh:**
1. `GET https://www.reddit.com/r/{sub}/new.json?limit=100`
2. `GET https://www.reddit.com/r/{sub}/hot.json?limit=25` (hourly only)

**Raw metrics over the last 60 min window (from `/new.json`, filter by `data.created_utc`):**
- `postCount` — number of posts
- `upvoteVelocity` — sum of `data.ups` across those posts
- `commentVelocity` — sum of `data.num_comments` across those posts

**Normalization to 0–100:**
1. Load the last `sentiment.sources.reddit.history-days` (default 14) of snapshots for this subreddit's pair from `SentimentSnapshotRepository`.
2. Parse each stored `raw_payload` to extract the three raw metrics (store them in the JSON so we don't need a separate column).
3. For each metric: compute mean + stddev over history, then `z = (current - mean) / max(stddev, epsilon)`, clamp `z` to `[-3, 3]`, map to `[0, 100]` where 50 = mean.
4. Final score = equal-weighted mean of the three normalized metrics, rounded to int.
5. If history has fewer than N samples (e.g. < 24), emit score = 50 and classification = `"Warming up"` — the UI should show this state explicitly.

**Classification bands:**

| Score range | Classification |
|-------------|----------------|
| `0–19`      | Quiet          |
| `20–39`     | Subdued        |
| `40–60`     | Normal         |
| `61–80`     | Heated         |
| `81–100`    | Frenzied       |

Reddit emits one `SentimentReading` per configured subreddit per refresh — mapped to the subreddit's pair, or pair = null for `cryptocurrency`.

**Explicit non-goals:** do not attempt to read post body text. Do not count bullish/bearish keywords. Do not call any LLM. Chatter intensity is *not* a directional signal — this must be clear in the UI.

---

#### Step 7 — `SentimentService`

```java
public interface SentimentService {
    BlendedSentiment currentBlended(String pair);                            // pair may be null
    Optional<SentimentReading> latest(SentimentSourceType source, String pair);
    List<SentimentReading> history(SentimentSourceType source, String pair, Duration window);
    void refreshAll();                                                        // called by scheduler
}
```

`currentBlended(pair)` algorithm:
1. Load the latest F&G snapshot (market-wide). Always a component.
2. Load the latest Reddit snapshot for the pair's mapped subreddit. If none, fall back to `r/cryptocurrency` (market-wide chatter). If neither, skip the Reddit component.
3. `blendedScore = fngWeight * fng.score + redditWeight * reddit.score`, weights normalized over the components actually present (so if Reddit is missing, F&G gets 100% weight).
4. `bias` derived from thresholds in config.

---

#### Step 8 — `SentimentScheduler`

`@Component` with three `@Scheduled` methods. Cron comes from config via `@Scheduled(cron = "${sentiment.sources.*.cron-*}")` so operators can retune without a redeploy. Enable Spring scheduling (`@EnableScheduling` may already be present on the application class — verify before adding).

| Method | Cron | Rationale |
|--------|------|-----------|
| `refreshFearGreed()` | `0 10 5 * * *` UTC | API updates once/day; fetch at 05:10 |
| `refreshRedditNew()` | `0 */30 * * * *` | Every 30 min for post/comment velocity |
| `refreshRedditHot()` | `0 15 * * * *` | Hourly for top posts |

Each method:
1. Checks `source.isEnabled()` — if disabled, return immediately.
2. Calls `source.fetch()` inside a try/catch. Any exception is logged at WARN level and swallowed — **one failing source must never break the others**.
3. Persists returned readings as `SentimentSnapshot` rows.

**Startup behaviour:** trigger one immediate F&G refresh on application ready (`@EventListener(ApplicationReadyEvent.class)`) so the DB is not empty for the UI on first boot. Do not auto-trigger Reddit — wait for the first scheduled tick so we don't block startup.

---

#### Step 9 — `SentimentController`

All endpoints under `/api/sentiment/**`. Must be documented in `API_CONTRACT.md` in the same PR — the root project instructions treat it as the canonical HTTP contract.

| Method | Path | Query params | Description |
|--------|------|--------------|-------------|
| GET | `/api/sentiment/current` | `pair` (optional) | Latest blended sentiment for a pair. Defaults to market-wide if `pair` omitted. Returns `BlendedSentimentDto`. |
| GET | `/api/sentiment/history` | `pair` (optional), `source` (optional: `FEAR_GREED`/`REDDIT`), `hours` (default 168) | Time-series of snapshots for charting. Returns `List<SentimentSnapshotDto>`. |
| GET | `/api/sentiment/sources` | — | Lists configured sources with `{ name, enabled, lastFetchedAt, lastScore, lastClassification }`. Used by the UI to show source health. |

The existing `GET /api/market/fear-greed` endpoint stays, backward compatible, still backed by `FearGreedService` directly (in-memory cache, snappy). The new `/api/sentiment/current` is the blended view.

DTO shapes:

```java
public record SentimentSnapshotDto(
    SentimentSourceType source,
    String pair,                    // nullable
    int score,
    String classification,
    Instant fetchedAt
) {}

public record BlendedSentimentDto(
    String pair,                    // nullable
    int blendedScore,
    String bias,                    // BlendedSentiment.Bias as string name
    List<ComponentDto> components
) {
    public record ComponentDto(SentimentSourceType source, int score, String classification) {}
}
```

---

#### Step 10 — `application.yml`

Add under root:

```yaml
sentiment:
  enabled: true
  weights:
    fear-greed: 0.6
    reddit: 0.4
  thresholds:                     # drive BlendedSentiment.Bias
    extreme-fear: 25
    fear: 40
    greed: 60
    extreme-greed: 75
  sources:
    fear-greed:
      enabled: true
      cron: "0 10 5 * * *"
    reddit:
      enabled: true
      user-agent: "revolut-trading-bot/1.0 (sentiment-dashboard)"
      cron-new: "0 */30 * * * *"
      cron-hot: "0 15 * * * *"
      history-days: 14
      subreddit-mapping:
        bitcoin: BTC-EUR
        ethtrader: ETH-EUR
        solana: SOL-EUR
        cryptocurrency: null      # market-wide
```

`SentimentConfig` is a `@ConfigurationProperties(prefix = "sentiment")` class (same pattern as `TradingConfig`), using nested static classes for `weights`, `thresholds`, `sources.fearGreed`, `sources.reddit`. Include `@Validated` with `@NotNull` / `@PositiveOrZero` where appropriate.

---

#### Step 11 — Tests (Spock / Groovy)

Under `src/test/groovy/com/stefo/revolut_trading_bot/sentiment/`:

- `RedditSourceSpec` — normalization math (mean/stddev/clamp), empty-history fallback to 50 / "Warming up", subreddit → pair mapping, 60-min window filtering, `cryptocurrency` mapped to pair = null.
- `SentimentServiceSpec` — blending with both sources, blending when Reddit is missing (F&G takes 100% weight), pair fallback from missing-subreddit to `r/cryptocurrency`, bias derivation from thresholds.
- `FearGreedSourceSpec` — delegates to `FearGreedService`, persists a market-wide reading.
- `SentimentSchedulerSpec` — one source throwing does not prevent another from persisting.
- `SentimentControllerSpec` — shape of the three endpoints (existing `DashboardControllerSpec` is the reference style).

---

#### Step 12 — API_CONTRACT.md update (same PR)

Append:
- `SentimentSourceType` enum under Section 2 (shared enums).
- The three `/api/sentiment/**` endpoints under Section 4.
- `BlendedSentimentDto` and `SentimentSnapshotDto` under the DTO section with exact JSON examples.

---

#### Explicitly NOT in this phase

- No new `StrategyType` value. No `SentimentStrategy`. Autowiring of `List<TradingStrategy>` must remain at 12 strategies.
- No changes to `SignalEngine`, `TradingLoop`, `RiskManager`, or `OrderExecutionService`.
- No veto / filter / gate on existing strategies' signals.
- No LLM calls. No HTML scraping. No Twitter/X integration. No RSS feeds.
- No local keyword-polarity lexicon.
- No new indexes on `signal_logs` / `trades` / `positions`.

---

#### Verification checklist

1. **DB migration:** `V6__sentiment_snapshots` appears in the Flyway log on startup; `\dt trading.sentiment_snapshots` shows the table and both indexes.
2. **Regression:** `GET /api/market/fear-greed` still returns the same shape and value as before.
3. **F&G ingestion:** after the startup `ApplicationReadyEvent` fires (or after manual refresh in dev), one row appears in `sentiment_snapshots` with `source='FEAR_GREED'`, `pair IS NULL`, a plausible `raw_payload`.
4. **Reddit ingestion:** after the first `*/30` tick, one row per configured subreddit. Initial score ≈ 50 with classification `"Warming up"` — that is correct until history fills.
5. **Blending endpoint:**
   ```
   curl 'http://localhost:8089/api/sentiment/current?pair=BTC-EUR'
   curl 'http://localhost:8089/api/sentiment/current'                  # market-wide
   curl 'http://localhost:8089/api/sentiment/history?pair=BTC-EUR&hours=48'
   curl 'http://localhost:8089/api/sentiment/sources'
   ```
   Component breakdown present in `/current`; F&G and Reddit components match the latest snapshots.
6. **Fallback:** temporarily drop the `solana` mapping, confirm `GET /api/sentiment/current?pair=SOL-EUR` falls back to `r/cryptocurrency` (or to F&G-only when no Reddit data exists) without erroring.
7. **Isolation (critical):** trading behaviour is unchanged. The 12 strategies still produce signals every 30 s, `signal_logs` rows still land, `trades` and `positions` behaviour matches pre-change. Tail `TradingLoop` logs through a full cycle to confirm.
8. **Tests:** `mvn test` — new Spock specs pass, all existing tests still pass.

---

#### Milestone

The dashboard shows a blended sentiment indicator (0–100) per pair with its F&G and Reddit components broken out, plus a 7-day history chart. Trading is untouched. After two to four weeks of accumulated snapshots, the UI can overlay sentiment onto trade PnL for visual correlation — providing the evidence base for a future "wire sentiment into risk / strategy" phase.
