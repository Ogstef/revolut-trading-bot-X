# Revolut X Trading Bot — Project Root

## Overview

Automated cryptocurrency trading bot. Runs 17 strategies (13 technical-analysis + 3 sentiment + 1 macro market-context) across 3 pairs and 5 candle intervals simultaneously. Each `(pair, strategy, interval)` triple is a fully isolated virtual portfolio with independent positions, trades, risk management, and P&L tracking.

**17 strategies × 3 pairs × 5 intervals = 255 independent SPOT portfolios** (when `sentiment.enabled: true`; 210 with sentiment off), all on a 30-second heartbeat.

Monorepo with three modules:
- **`revolut-trading-bot/`** — Java 21 / Spring Boot backend (REST API, trading engine, PostgreSQL, sentiment classifier + ingest)
- **`revolut-trading-bot-ui/`** — React 19 / TypeScript frontend (dashboard, charts, strategy comparison)
- **`scrapers/sentiment-scraper/`** — Python 3.12 microservice that scrapes Reddit + CryptoPanic and POSTs batches to the backend's ingest endpoint (Reddit/CryptoPanic APIs rejected: privacy + €50/wk cost)

**Backend-specific instructions:** `revolut-trading-bot/CLAUDE.md`
**Frontend-specific instructions:** `revolut-trading-bot-ui/CLAUDE.md`
**HTTP contract:** [`API_CONTRACT.md`](./API_CONTRACT.md) — canonical source for all `/api/**` endpoints, DTOs, and enums. Update it in the same PR whenever the contract changes.

---

## How to Run

```bash
# Backend (requires PostgreSQL on localhost:5432)
cd revolut-trading-bot
mvn spring-boot:run -Dspring-boot.run.profiles=local
# → http://localhost:8089

# Frontend
cd revolut-trading-bot-ui
npm install && npm run dev
# → http://localhost:5173

# Maven binary
/Applications/IntelliJ\ IDEA\ CE.app/Contents/plugins/maven/lib/maven3/bin/mvn
```

**Database:** PostgreSQL 16 via Docker — host `localhost:5432`, db `trading_bot`, schema `trading`, user `trading_bot`, password `secret`. Migrations applied automatically by Flyway on backend startup (V1–V11).

---

## Execution Unit

The fundamental unit is the **triple `(pair, strategy, interval)`**:

- **Pair:** BTC-EUR, ETH-EUR, SOL-EUR
- **Strategy:** 17 strategies — 13 TA (EMA_CROSSOVER, MACD, BOLLINGER, RSI_MOMENTUM, STOCH_RSI, TRIPLE_EMA, PARABOLIC_SAR, ADX_DI, CCI, MFI, DONCHIAN, ICHIMOKU, SUPERTREND) + 3 sentiment (REDDIT_SENTIMENT, CRYPTOPANIC_SENTIMENT, COMBINED_SENTIMENT; enabled via `sentiment.enabled`) + 1 macro context (MARKET_CONTEXT — Fear & Greed + order-book imbalance)
- **Interval:** 15m, 1h, 4h, 1d, 1w (configured in `application.yml` as `[15, 60, 240, 1440, 10080]`)

**Balance sharing:** balances are keyed by `(pair, strategy)` and shared across intervals. Positions, trades, risk, and P&L are isolated per interval.

---

## Current Mode: PAPER

All trading is simulated. Fee and slippage costs are modelled:
- Taker fee: 0.09% per side (`trading.costs.fee-rate: 0.0009`)
- Slippage: 0.05% per side (`trading.costs.slippage-rate: 0.0005`)
- `net_pnl` = gross PnL − entry fee − entry slippage − exit fee − exit slippage

No live order placement exists yet. See `docs/future-plans/plans.md` for the live trading roadmap.

---

## API Overview

All REST endpoints at `http://localhost:8089/api`. See `API_CONTRACT.md` for the full contract.

Key endpoints:

| Endpoint | Description |
|---|---|
| `GET /api/pairs` | Configured trading pairs |
| `GET /api/intervals` | Configured candle intervals |
| `GET /api/status` | Bot health + global metrics |
| `GET /api/strategies?pair=&interval=` | All 13 strategies for a pair+interval combo |
| `GET /api/strategies/{name}/stats?pair=&interval=` | Win rate, PnL, expectancy, fee drag |
| `GET /api/strategies/{name}/pnl?pair=&interval=` | Gross + net PnL breakdown (daily/weekly/monthly/all-time) |
| `GET /api/strategies/{name}/history?pair=&interval=&from=&to=` | Closed trades enriched with signal reason, TP/SL, R-multiple |
| `GET /api/today` | Today-only summary — KPIs, per-triple breakdown, closed trades, positions opened today |
| `GET /api/stats/all-triples` | One stats row per configured triple (210 rows TA-only, 255 with sentiment enabled) |
| `GET /api/positions/live` | All open positions across all triples |
| `GET /api/signals/current?pair=&interval=` | Latest signal per triple |
| `GET /api/activity?limit=&types=` | Bot event audit trail |
| `GET /api/candles?pair=&interval=&limit=` | Cached candlestick data |
| `GET /api/market/fear-greed` | Crypto Fear & Greed Index (1h in-memory cache) |
| `GET /api/market/context?pair=` | Per-pair Fear & Greed + bid/ask volume ratio (inputs to MARKET_CONTEXT strategy; 30s cache) |
| `GET /api/market/sentiment?pair=&source=&interval=` | Windowed Reddit/CryptoPanic/combined sentiment aggregate |
| `POST /api/sentiment/ingest/{reddit,cryptopanic}` | Scraper push — Bearer-authed, batched |

`?pair=` defaults to `BTC-EUR`; `?interval=` defaults to `15m` when omitted.
