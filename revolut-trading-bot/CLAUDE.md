# Revolut X Trading Bot — Backend

> **See also:** Root [`/CLAUDE.md`](../CLAUDE.md) for project-wide context.
> Frontend instructions: [`revolut-trading-bot-ui/CLAUDE.md`](../revolut-trading-bot-ui/CLAUDE.md).
> HTTP contract: [`../API_CONTRACT.md`](../API_CONTRACT.md) — single source of truth for all `/api/**` endpoints, DTOs, and enums. Update it in the same commit whenever you add/remove/rename an endpoint or change a DTO field.

---

## Tech Stack

- **Java 21**, **Spring Boot 4.0.5**, **Maven**
- **PostgreSQL 16** (Docker, port 5432, db: `trading_bot`, schema: `trading`, user: `trading_bot`, pw: `secret`)
- **Ta4j 0.16** — technical analysis indicators (EMA, RSI, MACD, Ichimoku, etc.)
- **BouncyCastle 1.83** — Ed25519 request signing for Revolut X API
- **OkHttp 4.12** — HTTP client for Revolut X API
- **Flyway** — database migrations (V1–V10 applied; V10 adds `sentiment_snapshots` + `llm_budget_daily` tables for the sentiment pipeline)
- **Lombok** — boilerplate reduction
- **Spock 2.4-M4 + Groovy** — test framework (project standard — use Spock for all new tests)
- **Base package:** `com.stefo.revolut_trading_bot`

---

## How to Run

```bash
# Requires: PostgreSQL on localhost:5432
mvn spring-boot:run -Dspring-boot.run.profiles=local
# Runs on http://localhost:8089
```

Maven binary: `/Applications/IntelliJ IDEA CE.app/Contents/plugins/maven/lib/maven3/bin/mvn`

---

## Execution Unit

The fundamental unit is the **quadruple `(pair, strategy, interval, vehicle)`** (was a triple before Phase 13) — each combination is a fully isolated virtual portfolio with its own open/closed positions, trades, and P&L tracking. `vehicle ∈ {SPOT, LEV_3X, LEV_5X, LEV_10X}`. SPOT is always active; `LEV_*X` activate only when `trading.leverage.enabled: true` and the ratio is in `trading.leverage.ratios`. Circuit breakers currently only scope to the SPOT triple — leveraged positions are gated by collateral availability (`VehicleBalanceService`).

**Current configuration:**
- **Pairs:** BTC-EUR, ETH-EUR, SOL-EUR
- **Strategies:** 16 (13 TA + 3 sentiment; see list below)
- **Intervals:** 15m, 1h, 4h, 1d, 1w

This gives **240 independent virtual portfolios** all running on the same 30-second heartbeat.

**Balance sharing:** balances are keyed by `(pair, strategy)` and shared across intervals. Positions, trades, risk checks, and P&L are isolated per interval.

---

## Package Structure

```
com.stefo.revolut_trading_bot/
├── config/          # TradingConfig, RevolutApiConfig, TelegramConfig, CorsConfig, JacksonConfig
├── model/
│   ├── entity/      # Position, Trade, Candlestick, SignalLog, BotEvent
│   ├── dto/         # OrderRequest/Response, BotStatusResponse, PnlBreakdown, PositionView,
│   │                #   TradeHistoryEntry, TripleStats, CurrentSignal, FearGreedResponse, …
│   └── enums/       # StrategyType, SignalType, OrderSide, OrderStatus, TradingMode,
│                    #   BotEventType, BotEventSeverity, TradingPair
├── market/          # MarketDataClient (Revolut API + Ed25519 signing), MarketDataService (BarSeries cache)
├── strategy/        # TradingStrategy interface, Signal record, SignalEngine, impl/
├── risk/            # RiskManager, RiskValidationResult, TakeProfitStopLossManager
├── execution/       # OrderExecutionService (router), PaperTradingService
├── portfolio/       # PortfolioService, PortfolioSnapshot, TradeService, TradingStats
├── repository/      # Spring Data JPA repositories
├── scheduler/       # TradingLoop (@Scheduled), BotStateService, DailySummaryScheduler
├── controller/      # DashboardController (all REST endpoints), TestController
├── service/         # BotStatusService, BotEventService, PositionService, SignalService,
│                    #   StrategyService, StatsAggregationService, ConfigService, FearGreedService
├── alert/           # AlertService, TelegramClient, TelegramMessageFormatter, DailySummaryData
└── exception/       # ApiException, SigningException
```

---

## Strategies (16 total)

All implement `TradingStrategy`, are `@Component` beans, and are autowired into `SignalEngine` via `List<TradingStrategy>`. The `Signal` record's `emaShort`/`emaLong`/`rsi` fields carry the most useful indicator values for each strategy (repurposed for display).

| Enum value | Display name | Key indicator |
|---|---|---|
| `EMA_CROSSOVER` | EMA Crossover | EMA(9) vs EMA(21) + RSI filter |
| `MACD` | MACD | MACD line vs signal line |
| `BOLLINGER` | Bollinger Bands | Price vs upper/lower bands |
| `RSI_MOMENTUM` | RSI Momentum | RSI crossover at 50 |
| `STOCH_RSI` | Stochastic RSI | StochRSI crossover at 0.20/0.80 |
| `TRIPLE_EMA` | Triple EMA | EMA(5) > EMA(13) > EMA(34) alignment |
| `PARABOLIC_SAR` | Parabolic SAR | Price crossing the SAR dot |
| `ADX_DI` | ADX + Directional Index | +DI/-DI crossover + ADX > 25 |
| `CCI` | CCI | CCI crossover at ±100 |
| `MFI` | Money Flow Index | MFI crossover at 20/80 |
| `DONCHIAN` | Donchian Breakout | Price breaking 20-bar high/low |
| `ICHIMOKU` | Ichimoku Cloud | TK cross + price above/below cloud |
| `SUPERTREND` | Supertrend | Supertrend line flip |
| `REDDIT_SENTIMENT` | Reddit Sentiment | Windowed Claude Haiku score ±0.35 (opt-in, needs scraper + API key) |
| `CRYPTOPANIC_SENTIMENT` | CryptoPanic Sentiment | Vote-derived score ±0.35 (opt-in, no LLM) |
| `COMBINED_SENTIMENT` | Combined Sentiment | Sample-weighted blend, require-agreement filter, ±0.40 |

### Sentiment pipeline

The three sentiment strategies share one ingest path. Outbound fetching from Reddit/CryptoPanic happens in the **`scrapers/sentiment-scraper/`** Python microservice — Java is a receiver. See `../CLAUDE.md` for the architecture overview. The pipeline is opt-in: `sentiment.enabled: false` by default; when disabled, ingest returns 503 and all three strategies return HOLD.

Budget: `LlmBudgetTracker` hard-caps Haiku spend at `€3/month` (see `sentiment.classifier.monthly-budget-usd`). Persistent state in `trading.llm_budget_daily` survives restarts.

---

## Database Schema

PostgreSQL, schema: `trading`. Managed by Flyway (V1–V8). All monetary fields: `DECIMAL(18,8)`.

**Tables:**

`positions` — id, pair, interval, side, entry_price, quantity, take_profit, stop_loss, status (OPEN/CLOSED), strategy_name, opened_at, closed_at, signal_reason

`trades` — id, position_id (FK), pair, interval, side, entry_price, exit_price, quantity, pnl, pnl_pct, strategy_name, exit_reason, executed_at, closed_at, trading_mode, **entry_fee, exit_fee, entry_slippage, exit_slippage, net_pnl, net_pnl_pct**

`candlesticks` — id, pair, interval, open/high/low/close_price, volume, timestamp. Unique on (pair, interval, timestamp).

`signal_logs` — id, pair, interval, strategy_name, signal_type, confidence, reason, ema_short, ema_long, rsi, current_price, created_at

`bot_events` — id, type, severity, pair, interval, strategy, title, detail, metadata (JSONB), created_at. Audit trail of lifecycle events.

**Interval label mapping:** `15` → `"15m"`, `60` → `"1h"`, `240` → `"4h"`, `1440` → `"1d"`, `10080` → `"1w"`. Helper: `TradingConfig.intervalLabel(int minutes)`.

---

## Key Configuration (`application.yml`)

```yaml
trading:
  pairs: [BTC-EUR, ETH-EUR, SOL-EUR]
  intervals: [15, 60, 240, 1440, 10080]
  mode: PAPER
  polling-interval-seconds: 30
  paper-balance: 10000.00          # fallback when pair/strategy not in strategy-balances
  strategy-balances:               # pair → strategy → EUR starting balance
    BTC-EUR:
      EMA_CROSSOVER: 10000.00
      # ... all 13 strategies
  costs:
    fee-rate: 0.0009               # per-side taker fee (0.09%)
    slippage-rate: 0.0005          # per-side slippage estimate (0.05%)
  risk:
    take-profit-pct: 12
    stop-loss-pct: 4
    max-position-pct: 2.0
    max-concurrent-positions: 3
    max-daily-loss-pct: 10.0
    max-consecutive-losses: 15

telegram:
  enabled: ${TELEGRAM_ENABLED:false}
  bot-token: ${TELEGRAM_BOT_TOKEN:}
  chat-id: ${TELEGRAM_CHAT_ID:}
  send-trade-notifications: true
  send-daily-summary: true
  daily-summary-cron: "0 */30 * * * *"
```

---

## Fee & Slippage Modelling

Implemented via `TradingConfig.Costs` — a simple flat-rate approach (not the complex per-pair FeeModel interface). Applied in `PaperTradingService`:

- **Entry:** `entryNotional × feeRate` → `entry_fee`; `entryNotional × slippageRate` → `entry_slippage`
- **Exit:** same formula with exit notional
- **`net_pnl`** = gross pnl − entry_fee − entry_slippage − exit_fee − exit_slippage
- `pnl` column remains **gross** (no semantic change). `net_pnl` is the new field.

`TradingStats` exposes: `totalPnl` (gross), `netPnl`, `totalFees`, `totalSlippage`, `feeDragPct`, `netExpectancy`.
`PnlBreakdown` exposes: `daily/weekly/monthly/allTime` (gross) + `dailyNet/weeklyNet/monthlyNet/allTimeNet`.

---

## Trading Loop

Every 30 seconds (`TradingLoop`):

1. For each `(pair, interval)`: fetch candles once via `MarketDataService.fetchBarSeriesForPairAndInterval(pair, intervalLabel)` — all strategies share the same `BarSeries`
2. For each strategy: evaluate on the `BarSeries`, inject interval via `signal.withInterval(intervalLabel)`
3. Group signals by `(pair, interval)`
4. For each group: get current price from ticker, then for each strategy signal:
   - Monitor TP/SL for open positions (scoped to the triple)
   - Check `RiskManager` constraints (also scoped to the triple)
   - Execute signal (BUY/SELL/HOLD) via `OrderExecutionService`
5. Log via `AlertService` (Telegram + SLF4J)

Total signals per cycle: 13 strategies × 3 pairs × 5 intervals = **195 signals**

---

## Risk Manager

All checks are scoped to the `(pair, interval, strategy)` triple:
- Max `maxConcurrentPositions` open at once
- Max `maxPositionPct` of portfolio per trade
- Daily loss circuit breaker (`maxDailyLossPct`)
- Consecutive loss circuit breaker (`maxConsecutiveLosses`)
- TP/SL monitored every 30s regardless of interval

---

## Key DTOs

**`TradingStats`** (record, 15 fields): `totalTrades, winningTrades, losingTrades, winRate, totalPnl, averageWin, averageLoss, bestTrade, worstTrade, expectancy, netPnl, totalFees, totalSlippage, feeDragPct, netExpectancy`

**`PnlBreakdown`** (record, 8 fields): `daily, weekly, monthly, allTime, dailyNet, weeklyNet, monthlyNet, allTimeNet`

**`TradeHistoryEntry`** (record, 26 fields): all trade fields + position-side enrichment (`entrySignalReason, takeProfit, stopLoss, openedAt`) + derived analytics (`holdingDurationSeconds, rMultiple`) + cost fields (`entryFee, exitFee, entrySlippage, exitSlippage, netPnl, netPnlPct`)

---

## API Endpoints (current)

All at `http://localhost:8089/api`. See `API_CONTRACT.md` for full field-level detail.

| Method | Path | Description |
|---|---|---|
| GET | `/api/status` | Bot health + global metrics |
| GET | `/api/pairs` | Configured trading pairs |
| GET | `/api/intervals` | Configured intervals with labels |
| GET | `/api/positions?pair=` | Open positions |
| GET | `/api/positions/live` | All open positions across all triples, enriched |
| GET | `/api/trades?limit=&pair=` | Recent closed trades |
| GET | `/api/stats` | Global aggregate TradingStats |
| GET | `/api/pnl` | Global PnlBreakdown (gross + net per period) |
| POST | `/api/emergency-stop` | Pause trading loop |
| POST | `/api/resume` | Resume trading loop |
| POST | `/api/config` | Runtime config update |
| GET | `/api/strategies?pair=&interval=` | All strategies for a pair+interval |
| GET | `/api/strategies/{name}/positions?pair=&interval=` | Open positions for a strategy |
| GET | `/api/strategies/{name}/trades?pair=&interval=&limit=` | Closed trades for a strategy |
| GET | `/api/strategies/{name}/history?pair=&interval=&from=&to=` | Trade history with enrichment |
| GET | `/api/strategies/{name}/stats?pair=&interval=` | TradingStats for a strategy |
| GET | `/api/strategies/{name}/pnl?pair=&interval=` | PnlBreakdown for a strategy |
| GET | `/api/strategies/{name}/signals?pair=&interval=&limit=` | Signal history |
| GET | `/api/signals/summary?pair=&interval=` | Signal counts per strategy |
| GET | `/api/signals/current?pair=&interval=` | Latest signal per (pair, interval, strategy) triple |
| GET | `/api/stats/all-triples` | One TripleStats row per configured triple (195 rows) |
| GET | `/api/activity?limit=&types=` | Bot event audit trail |
| GET | `/api/candles?pair=&interval=&limit=` | Cached candlestick data |
| GET | `/api/market/fear-greed` | Fear & Greed Index (1h in-memory cache, not persisted) |
| GET | `/api/market/sentiment?pair=&source=&interval=` | Windowed Reddit/CryptoPanic/combined aggregate |
| POST | `/api/sentiment/ingest/reddit` | Scraper push — Bearer-authed, `RedditIngestRequest` body |
| POST | `/api/sentiment/ingest/cryptopanic` | Scraper push — Bearer-authed, `CryptoPanicIngestRequest` body |

When `?pair=` is omitted → defaults to first configured pair (`BTC-EUR`).
When `?interval=` is omitted → defaults to first configured interval (`15m`).

---

## Revolut X API

**Base URL:** `https://revx.revolut.com/api/1.0`

**Auth:** Ed25519 signature on every request. Headers: `X-Revx-API-Key`, `X-Revx-Timestamp`, `X-Revx-Signature`. Message to sign: `{timestamp}{METHOD}{path}{queryString}{body}`. Private key path from env var `REVOLUT_PRIVATE_KEY_PATH`.

**Candle endpoint:** `GET /candles/{symbol}?interval={minutes}&since={epochMs}&until={epochMs}`. Response field is `start` (not `timestamp`).

**Rate limit:** 1000 req/day for limit orders; 1000 req/min general.

---

## Telegram Notifications

`AlertService` → `TelegramClient` → Revolut Bot API. Triggered on:
- Position opened/closed
- Circuit breaker tripped/reset
- Bot stopped/resumed
- Daily summary (via `DailySummaryScheduler`)

Configured via env vars `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`. Disabled by default (`telegram.enabled: false`).

---

## Code Style

- **Lombok:** `@Data`, `@Builder`, `@Slf4j` everywhere. `@RequiredArgsConstructor` for constructor injection.
- **Records:** Use Java records for DTOs, value objects, and `Signal`.
- **Methods:** Under 15 lines, single responsibility.
- **Money:** Always `BigDecimal`, never `double`/`float`.
- **Logging:** Comprehensive SLF4J. Log every signal, trade decision, API call, and error.
- **Repositories:** `Optional` returns where applicable.
- **Testing:** Spock/Groovy. Unit-test strategy logic, risk manager, and services. No Spring context in unit tests — instantiate directly with mocks.
- **No comments** explaining what the code does. Only comment non-obvious WHY.

---

## Safety Rules

- **NEVER place real orders unless `trading.mode` is `LIVE`.**
- **NEVER store API keys in code or yml** — always environment variables.
- **NEVER risk more than `max-position-pct` per trade.**
- **ALWAYS respect the circuit breaker.**
- **ALWAYS log before executing** — every order attempt must be logged before the API call.
