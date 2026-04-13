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
