# 13 — Daily Routine: Claude Morning Analysis [P1]

**Type:** Scheduled Claude Code routine (no backend code in v1)
**Effort:** ~2–3 hours end-to-end (mostly SQL drafting + prompt tuning)
**Value:** High — turns the existing raw-numbers Telegram summary into an actionable morning briefing with concrete tuning suggestions
**Dependencies:** None. Uses `mcp__postgres-vps__query` (already configured) + Rev-Trader Telegram bot (already deployed on VPS).

---

## Context

The backend already pushes a nightly raw-numbers summary at 21:00 UTC via `DailySummaryScheduler` → Telegram: trades closed, win rate, gross/net PnL, fee drag, top 3 winners/losers, leverage breakdown. It's a dashboard in message form.

What's missing is **judgement**: which `(pair, strategy, interval, vehicle)` quadruples are trending up vs. mean-reverting, whether fee drag is eating real profit, whether recent tuning actually helped, where to tune next. The goal is a short written analysis each morning at **08:00 Europe/Athens** with:

- What worked today (vs. 7d / 30d baselines)
- What didn't
- Open risk right now
- 2–3 concrete tuning suggestions grounded in today's data

No new backend code is needed for v1 — this is entirely a Claude Code scheduled routine (`CronCreate`) that queries the VPS Postgres directly and delivers to Telegram via the Bot API.

---

## Decisions already made (do not re-ask)

| Question | Answer |
|---|---|
| Delivery channel | **Telegram** (Rev-Trader bot, chat ID `6668373521`) |
| Schedule | **08:00 Europe/Athens**, daily |
| Data source | **Direct Postgres** via `mcp__postgres-vps__query` |
| Baseline | **Today vs. 7-day AND 30-day rolling averages — with adaptive fallback (see "Cold-start" below)** |

---

## Cold-start behavior — IMPORTANT

Leveraged paper trading (`LEV_3X / LEV_5X / LEV_10X`) went live on the VPS on **2026-04-24**. `SPOT` rows have full history; `LEV_*X` rows start from that date.

This means **7d rolling baselines for LEV_*X rows are meaningless until ~2026-05-01**, and **30d baselines for LEV_*X rows are meaningless until ~2026-05-24**.

The routine prompt MUST handle this adaptively — do not hardcode a "warm-up mode on/off" switch that has to be toggled by hand. Encode the rule per quadruple:

- **For each `(pair, strategy, interval, vehicle)` quadruple reported:**
  - Count baseline trades (last 7d excluding today). If `< 3`, skip the 7d delta for this row.
  - Count baseline trades (last 30d excluding today). If `< 3`, skip the 30d delta for this row.
  - If BOTH baselines are thin, mark the row as `(NEW)` and report **absolute numbers only** — today's count, today's net PnL, today's win rate — with no delta comparison.
- **At TL;DR level:** if `>50%` of today's reported quadruples have thin baselines, lead with absolute totals (trades, net PnL €) rather than a trend statement, and add a one-line note: `Leveraged vehicles still warming up — N/M rows have enough baseline history.`
- **Silence rule still applies:** even during warm-up, if fewer than 5 trades closed today AND nothing is obviously anomalous, emit the one-line quiet-day message.

This nuance fades naturally: once every active quadruple has ≥3 baseline trades, the routine just starts reporting deltas. No config change needed.

---

## Architecture

```
  CronCreate (persistent, 0 8 * * * Europe/Athens)
        │
        ▼
  Claude routine prompt (self-contained, defined below)
        │
        ├── mcp__postgres-vps__query  → today's trades, 7d/30d baselines, open positions, bot_events
        │
        ├── (in-session reasoning)    → rank, compare, form hypotheses, draft improvement suggestions
        │
        └── WebFetch → api.telegram.org/bot<TOKEN>/sendMessage  → Rev-Trader Telegram chat
```

Nothing persists except the Telegram message. A second delivery channel (markdown file per day) is deferred — see "Extensions".

---

## Data the routine pulls

All queries against the VPS Postgres, schema `trading`, via `mcp__postgres-vps__query`. The routine composes and runs these itself — do NOT pre-materialize them as views or a new backend endpoint in v1.

1. **Today's activity** — trades closed in the last 24h, grouped by `(pair, strategy_name, interval, vehicle)`:
   - count, wins, losses
   - `SUM(pnl)` gross, `SUM(net_pnl)` net
   - `AVG(net_pnl_pct)`
   - `SUM(entry_fee + exit_fee + entry_slippage + exit_slippage)`
2. **7-day rolling baseline** — same shape, last 7 days excluding today, converted to per-day averages so "today" can be compared to a typical day.
3. **30-day rolling baseline** — same shape, last 30 days excluding today, per-day averages.
4. **Top movers today** — top 3 net PnL and bottom 3 net PnL quadruples.
5. **Fee drag check** — triples where `SUM(fees + slippage) / ABS(SUM(pnl)) > 0.30` over the last 7 days.
6. **Exit-reason distribution today** — count by `exit_reason` (`TP_HIT`, `SL_HIT`, `SIGNAL_EXIT`, `LIQUIDATED`). Spike in `SL_HIT` or `LIQUIDATED` is a red flag.
7. **Open positions snapshot** — `trading.positions WHERE status='OPEN'`:
   - count, total notional, pair/strategy/interval/vehicle mix
   - any leveraged position within 5% of its `liquidation_price` (current price ≈ latest `trading.candlesticks.close_price` per pair)
8. **Bot events in last 24h** — `trading.bot_events WHERE type IN ('CIRCUIT_BREAKER_TRIPPED', 'POSITION_LIQUIDATED') AND created_at > now() - interval '24 hours'`.

No new indexes needed — existing V9 indexes (`idx_trades_vehicle_pair_strat_interval`, etc.) cover these.

### Reference schema (verify at implementation time)

- **`trading.trades`** — `pair, interval, strategy_name, side, entry_price, exit_price, quantity, pnl (gross), pnl_pct, net_pnl, net_pnl_pct, entry_fee, exit_fee, entry_slippage, exit_slippage, exit_reason, executed_at, closed_at, vehicle, leverage, collateral, funding_fees, liquidated`
- **`trading.positions`** — `pair, interval, strategy_name, side, entry_price, quantity, take_profit, stop_loss, status, opened_at, closed_at, signal_reason, vehicle, leverage, collateral, notional, liquidation_price, funding_fees_accrued`
- **`trading.bot_events`** — `type, severity, pair, interval, strategy, title, detail, metadata (JSONB), created_at`
- **`trading.candlesticks`** — `pair, interval, open/high/low/close_price, volume, timestamp`
- Interval label mapping: `15→"15m"`, `60→"1h"`, `240→"4h"`, `1440→"1d"`, `10080→"1w"`
- Vehicle values: `SPOT`, `LEV_3X`, `LEV_5X`, `LEV_10X`

---

## Output: Telegram message shape

Telegram HTML, ~500 words max, same tag style as `TelegramMessageFormatter.formatDailySummary` (`<b>`, `•`) so it reads consistently next to the existing 21:00 summary.

```
<b>🧠 DAILY ANALYSIS — {date}</b>

<b>TL;DR:</b> one sentence. Full-history rows: above/below trend. Warm-up rows: lead with absolute totals + note baseline coverage.

<b>What worked</b>
 • 2–3 quadruples that outperformed (delta vs baseline if available, else absolute + (NEW))
 • Call out a pattern (e.g. "4h SUPERTREND on BTC keeps beating its 30d avg by 2×")

<b>What didn't</b>
 • 2–3 underperformers (delta vs baseline or absolute + (NEW))
 • SL_HIT clusters, liquidations, fee-dragged strategies

<b>Open risk</b>
 • Open position count, total notional
 • Any leveraged position within 5% of liquidation
 • Active circuit breakers

<b>Areas for improvement</b>
 • 2–3 concrete tuning suggestions grounded in today's data
 • e.g. "STOCH_RSI on 15m had 4 SL_HITs in 24h vs 1.2/day avg — consider widening stop or raising confidence threshold"
 • e.g. "BOLLINGER on SOL-EUR 1h fee drag 42% — expectancy barely positive; review min-move filter"
 • During warm-up: avoid suggestions based solely on LEV_*X performance; wait for baseline to fill unless the signal is absolute (e.g. liquidation).
```

### Rules the routine prompt must encode

- **Ground every claim in a number.** When baseline is sufficient, cite the delta vs. baseline. When baseline is thin (see Cold-start section), cite absolute numbers and mark the row `(NEW)` — never fabricate or extrapolate a delta from <3 baseline trades.
- **Be blunt about losers.** If a quadruple is consistently bleeding, say so.
- **Prefer specific fixes over generic advice.** "Widen SL on X from 4% to 5%" beats "review risk parameters".
- **Silence when quiet.** If fewer than 5 trades closed AND nothing moved >5% from baseline (or, during warm-up, nothing is absolutely anomalous — e.g. a liquidation, circuit breaker, fee drag >50%), output only:
  `Quiet day — N trades closed, net €X, nothing noteworthy.` and stop. No padding.

---

## Telegram delivery mechanism

Default path — WebFetch against the Bot API:

```
GET https://api.telegram.org/bot<TELEGRAM_BOT_TOKEN>/sendMessage
    ?chat_id=6668373521
    &parse_mode=HTML
    &text=<URL-ENCODED MESSAGE>
```

**Credential:** `TELEGRAM_BOT_TOKEN`. Source value from VPS env (`/etc/revolut-trading-bot.env`) and mirror into whatever env the scheduled routine can read (confirm the exact mechanism at implementation time — candidates: `~/.claude/.env`, a `CronCreate` env-var parameter, or a user-scoped secret store). **Do NOT commit the token anywhere in this repo.**

**Fallback (only if WebFetch proves insufficient — long URLs, network restrictions, POST-only needs):**
Add a tiny backend endpoint `POST /api/admin/telegram/send` accepting `{text}` behind a shared-secret `X-Admin-Token` header, forwarding to the existing `TelegramClient.sendMessage()`. ~20 lines in `DashboardController.java` + a matching entry in `API_CONTRACT.md`. Skip unless the default fails.

---

## Files touched

**v1 expected:** zero backend/frontend file changes. Work happens entirely in the Claude Code scheduling layer (CronCreate + routine prompt) and in the VPS DB (read-only queries).

**Code referenced (not modified):**
- `revolut-trading-bot/src/main/java/com/stefo/revolut_trading_bot/service/StatsAggregationService.java` — mirror its metric definitions (expectancy, net expectancy, fee drag %) in the SQL.
- `revolut-trading-bot/src/main/java/com/stefo/revolut_trading_bot/alert/TelegramMessageFormatter.java` (method `formatDailySummary`) — match HTML tag style and section ordering.
- `revolut-trading-bot/src/main/resources/db/migration/V9__*.sql` — confirm `vehicle`, `leverage`, `liquidation_price` column names before writing SQL.

**Fallback-only (skip unless needed):**
- `revolut-trading-bot/src/main/java/com/stefo/revolut_trading_bot/controller/DashboardController.java` — add `POST /api/admin/telegram/send`.
- `API_CONTRACT.md` — document the endpoint.

---

## Implementation steps

1. **Draft and test the 8 SQL queries** against the VPS DB via `mcp__postgres-vps__query`. Spot-check results against `/api/stats/all-triples` from the deployed backend.
2. **Write the routine prompt** — a single self-contained prompt that: (a) runs the SQL, (b) applies the analysis rules above, (c) composes the Telegram HTML, (d) URL-encodes it, (e) fires the WebFetch to api.telegram.org. The prompt must include enough context to run cold in a fresh remote session (schema reminders, metric definitions, output template, silence rule).
3. **Dry run interactively** — invoke the prompt once manually in a normal Claude session, inspect the drafted Telegram HTML, tune wording and thresholds until it reads well.
4. **Send a marked test message** — prefix with `TEST —` and confirm it lands in chat `6668373521`.
5. **Create the cron** — `CronCreate` with `0 8 * * *` in `Europe/Athens` (fallback `0 6 * * *` UTC if TZ not supported — accepts 1h summer DST drift, fine for a morning report), `persistent: true`. Confirm via `CronList`.
6. **Observe the first real firing** at 08:00 Athens. Verify delivery.
7. **Iterate over the first week** — tune baseline thresholds (what counts as "outperformance") and the silence rule (minimum trades for a full report) based on whether the reports are actually useful.

---

## Verification checklist

- [ ] Each SQL query returns plausible numbers when run manually via `mcp__postgres-vps__query`.
- [ ] Numbers cross-check against `/api/stats/all-triples` and `/api/pnl` on the deployed backend.
- [ ] **Warm-up handling verified:** a LEV_*X quadruple with <3 baseline trades shows as `(NEW)` with absolute numbers only, no fabricated delta.
- [ ] **Warm-up handling verified:** a SPOT quadruple with full baseline history shows a proper delta comparison.
- [ ] A dry-run Telegram message lands in chat `6668373521` with correct HTML rendering (bold tags, bullets, no broken entities).
- [ ] `CronList` shows the job with the expected schedule.
- [ ] First automated 08:00 firing delivers a non-empty message.
- [ ] On a known "quiet" day (few trades), the output uses the silence one-liner, not padded sections.

---

## Extensions (deferred, not v1)

- Persist each day's report as `daily-reports/YYYY-MM-DD.md` so reports are diffable week-over-week.
- Track which suggestions were acted on (`daily-reports/suggestions.log` with done/skipped markers) and feed the still-open suggestions back into the next day's prompt for continuity.
- Weekly meta-report on Sundays that reads the week's daily reports and surfaces repeating themes.
- Add a second channel (in-app push, email digest) once the Telegram format has stabilized.

---

## Fresh-chat pickup prompt

> Read `docs/future-plans/13-daily-routine-analysis.md` and implement it. Start with step 1 (draft and test the 8 SQL queries against the VPS DB via `mcp__postgres-vps__query`). Do not modify backend code in v1 — the whole thing lives in the Claude Code scheduling layer.
