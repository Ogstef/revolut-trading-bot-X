I'm building an automated cryptocurrency trading bot in Java with Spring Boot that uses the Revolut X REST API. The project is already initialized with Spring Boot 4.0.5, Java 21, and all dependencies are in pom.xml. I need you to help me build this incrementally, phase by phase.

## Project Context

- **Group**: com.stefanos
- **Artifact**: revolut-trading-bot
- **Base package**: com.stefanos.revolut_trading_bot
- **Database**: PostgreSQL 17 running locally in Docker on port 5432, database name `trading_bot`, user `trading_bot`, password `secret`
- **Trading mode**: PAPER only for now (simulate trades, never place real orders)
- **Trading pair**: BTC-EUR (single pair for v1)
- **Strategy**: EMA(9)/EMA(21) crossover with RSI(14) filter
- **Take Profit**: 5%, **Stop Loss**: 3%

## Dependencies already in pom.xml

From Spring Initializr: Spring Web, Spring Data JPA, PostgreSQL Driver, Spring Boot Actuator, Validation, Flyway Migration, Lombok

Manually added: Ta4j (org.ta4j:ta4j-core:0.16), BouncyCastle (org.bouncycastle:bcprov-jdk18on:1.83), OkHttp (com.squareup.okhttp3:okhttp:4.12.0), MapStruct (org.mapstruct:mapstruct:1.6.3 + processor), Spock Framework (org.spockframework:spock-core:2.4-M4-groovy-4.0 + spock-spring)

## Revolut X API Details

- **Base URL**: `https://revx.revolut.com/api/1.0`
- **Authentication**: Custom Ed25519 signature scheme. Every authenticated request needs 3 headers:
  - `X-Revx-API-Key`: 64-character alphanumeric API key (from env var `REVOLUT_API_KEY`)
  - `X-Revx-Timestamp`: Unix epoch timestamp in milliseconds
  - `X-Revx-Signature`: Base64-encoded Ed25519 signature of the message string
- **Message to sign**: Concatenate WITHOUT separators: `{timestamp}{HTTP_METHOD}{path}{queryString}{body}`
  - Example: `1765360896219POST/api/1.0/orders{"client_order_id":"...","symbol":"BTC-USD","side":"BUY","order_configuration":{"limit":{"base_size":"0.1","price":"90000.1"}}}`
  - For GET requests with no body, just omit the body part
  - Query string should NOT include the `?` prefix
- **Private key**: Ed25519 PEM file, path from env var `REVOLUT_PRIVATE_KEY_PATH`
- **Rate limit**: 1000 requests per minute for order endpoints

### Key Endpoints

Public (no auth):
- `GET /public/symbols` — available trading pairs
- `GET /public/trades?symbol=BTC-EUR` — latest 100 market trades
- `GET /public/order-book?symbol=BTC-EUR` — bids/asks (5 levels)

Authenticated:
- `GET /market-data/candles?symbol=BTC-EUR&interval=15m` — historical OHLCV candles
- `GET /balance` — account balances
- `POST /orders` — place order (limit or market)
- `GET /orders/active` — open orders
- `DELETE /orders/{id}` — cancel order
- `GET /trades` — client trade history (fills)

### Order Placement Body Structure
```json
{
  "client_order_id": "uuid",
  "symbol": "BTC-EUR",
  "side": "BUY",
  "order_configuration": {
    "market": {
      "quote_size": "100.00"
    }
  }
}
```

For limit orders, replace `market` with:
```json
"limit": {
  "base_size": "0.001",
  "price": "62000.00"
}
```

## Architecture Overview

### Package Structure
```
com.stefanos.revoluttradingbot/
├── config/          # @ConfigurationProperties classes
├── model/
│   ├── entity/      # JPA entities (Trade, Position, Candlestick, SignalLog)
│   ├── dto/         # DTOs (OrderRequest, OrderResult, TradingStats, etc.)
│   └── enums/       # SignalType, OrderSide, OrderStatus, TradingPair, TradingMode
├── market/          # MarketDataClient (API calls), MarketDataService (buffering + BarSeries)
├── strategy/        # Strategy interface, Signal record, SignalEngine, impl/EmaCrossoverStrategy
├── risk/            # RiskManager, TakeProfitStopLossManager
├── execution/       # OrderExecutionService (router), PaperTradingService, LiveTradingService
├── portfolio/       # PortfolioService, TradeService
├── repository/      # Spring Data JPA repositories
├── scheduler/       # TradingLoop (@Scheduled orchestrator)
├── controller/      # DashboardController (REST monitoring API)
├── alert/           # AlertService (SLF4J logging for v1)
└── exception/       # Custom exceptions
```

### Database Schema (trading schema)
4 tables in a `trading` schema:
1. **positions** — id, pair, side, entry_price, quantity, take_profit, stop_loss, status (OPEN/CLOSED), opened_at, closed_at, signal_reason
2. **trades** — id, position_id (FK), pair, side, entry_price, exit_price, quantity, pnl, pnl_pct, exit_reason (TP_HIT/SL_HIT/SIGNAL_EXIT/MANUAL), executed_at, trading_mode (PAPER/LIVE)
3. **candlesticks** — id, pair, interval, open/high/low/close prices, volume, timestamp (unique constraint on pair+interval+timestamp)
4. **signal_logs** — id, pair, signal_type, confidence, reason, ema_short, ema_long, rsi, current_price, created_at

All monetary fields use DECIMAL(18,8). Use Flyway migrations in `src/main/resources/db/migration/`.

### Strategy Logic (EmaCrossoverStrategy)
Using Ta4j library:
- **BUY signal**: EMA(9) crosses above EMA(21) AND RSI(14) is between 30-70 AND price is above EMA(21)
- **SELL signal**: EMA(9) crosses below EMA(21) OR RSI(14) > 70 (overbought)
- **HOLD**: everything else

### Risk Management Rules
- Max 2% of portfolio per position
- Max 3 concurrent positions
- Max 5% daily loss → circuit breaker trips, bot stops trading
- Max 5 consecutive losses → circuit breaker trips
- TP = entry_price × 1.05, SL = entry_price × 0.97

### Trading Loop (every 30 seconds via @Scheduled)
1. Fetch latest candles from Revolut X API
2. Build/update Ta4j BarSeries
3. Run SignalEngine → get BUY/SELL/HOLD
4. If BUY/SELL → validate through RiskManager → calculate position size → execute via PaperTradingService
5. Monitor open positions against current price → check TP/SL
6. Log everything

### Configuration (application.yml)
Use @ConfigurationProperties with prefix `trading` for strategy params, risk params, polling interval, and trading mode. Use `revolut` prefix for API config. All secrets via environment variables.

## What I Want You To Build — Phase 1

Start with the foundation. Build these in order:

1. **application.yml** with all config (datasource, JPA, trading config, revolut API config, actuator)
2. **Flyway migration** V1__init_schema.sql with all 4 tables + indexes
3. **Enums**: SignalType, OrderSide, OrderStatus, TradingPair, TradingMode
4. **Entities**: Position, Trade, Candlestick, SignalLog (with proper JPA annotations, Lombok)
5. **Repositories**: Spring Data JPA interfaces with custom query methods we'll need
6. **Config classes**: TradingConfig and RevolutApiConfig as @ConfigurationProperties

After this phase is done and the app boots cleanly with Flyway running, I'll ask you for Phase 2 (MarketDataClient with Ed25519 signing).

## Code Style Preferences
- Use Lombok (@Data, @Builder, @NoArgsConstructor, @AllArgsConstructor) on entities
- Use Java records for DTOs and value objects where appropriate
- Methods should be under 15 lines, single responsibility
- Use BigDecimal for all monetary values, never double/float
- Comprehensive SLF4J logging (use @Slf4j from Lombok)
- Always use Optional returns from repositories where applicable
- Include proper validation annotations on config classes (@NotNull, @Positive, etc.)

Build Phase 1 now. Create all files, make sure it compiles, and verify Flyway can run the migration.
