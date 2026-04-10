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

### Phase 4 — Risk Management & Paper Trading
- RiskManager — validates trades against all risk rules, calculates position size
- TakeProfitStopLossManager — calculates TP/SL prices, checks exit conditions
- PaperTradingService — simulates order fills using current market price, tracks virtual balance
- OrderExecutionService — routes to Paper or Live based on config
- **Milestone:** Full paper trade execution from signal to simulated fill

### Phase 5 — Trading Loop & Portfolio
- TradingLoop — @Scheduled main heartbeat, orchestrates the full cycle
- PortfolioService — tracks open positions, calculates PnL
- TradeService — records completed trades, computes win rate and statistics
- Open position monitoring — checks TP/SL on every cycle
- Circuit breaker logic — stops bot on excessive losses
- **Milestone:** Bot runs autonomously in paper mode

### Phase 6 — Monitoring Dashboard
- DashboardController REST API:
    - `GET /api/status` — bot status, current mode, circuit breaker state
    - `GET /api/positions` — open positions with live PnL
    - `GET /api/trades` — trade history with stats
    - `GET /api/stats` — win rate, total PnL, average trade duration
    - `GET /api/pnl` — daily/weekly/monthly PnL breakdown
    - `POST /api/config` — update trading parameters at runtime
    - `POST /api/emergency-stop` — immediately stop trading
- AlertService — SLF4J structured logging (v1), Telegram webhook (v2)
- **Milestone:** Can monitor and control bot remotely via REST

### Phase 7 — Backtest & Go Live
- Backtest mode — replay historical candles through the strategy, compute hypothetical results
- Run paper mode for 2-4 weeks, analyze results
- LiveTradingService — real Revolut X order placement via POST /orders
- Start with absolute minimum position sizes
- **Milestone:** Real trades with real money

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
