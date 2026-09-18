# News-Strat — Sentiment-Driven Trading Strategy

**Type:** New `TradingStrategy` implementation (strategy #14)
**Effort:** ~5 working days
**Value:** Medium — adds a non-technical signal source; best used as a measurement experiment in PAPER for 2–4 weeks, then kept / pivoted to filter / dropped
**Dependencies:** None (fully additive to existing architecture)
**Cost:** **Hard cap €3/month** enforced in config. Expected ~€1.70/month with pre-filters active; $0 for Reddit & CryptoPanic. Classifier fails closed on cap breach — scraper keeps writing raw posts for post-hoc backfill once budget resets.

> **Relationship to `10-news-sentiment.md`:** that older plan models sentiment as a **damping multiplier** applied to the signals emitted by the existing 13 TA strategies (via a `MarketContext` record). This plan is a **different architectural shape** — sentiment becomes a fully standalone 14th strategy that emits its own BUY/SELL/HOLD signals and gets its own isolated P&L tracking in the existing 195-triple dashboard (making it 210 triples). The standalone-strategy approach was chosen so we can **directly measure sentiment's predictive value in isolation** before committing to filter-layer work. The two plans are not mutually exclusive — if standalone P&L is uninteresting but sentiment still looks informative, `10-news-sentiment.md`'s multiplier approach becomes the natural Phase-2 follow-up, and the `SentimentService` built here is already the right shape to feed it.

---

## Purpose

Introduce a 14th trading strategy driven by **social + news sentiment** instead of candle-based technical analysis. Sources: **Reddit** (primary, free) and **CryptoPanic** (secondary, free tier). Per-post sentiment scoring via **Claude Haiku** batched classification. The strategy plugs into the existing `TradingStrategy` interface and runs alongside the current 13 with **zero modification** to `SignalEngine`, `TradingLoop`, `RiskManager`, or any other existing strategy. After implementation the SPOT portfolio count grows by **14 × 3 × 5 = 210** (was 195 across the existing 13 strategies), and the new strategy is directly comparable to the other 13 in the existing dashboard (win rate, expectancy, PnL breakdown, fee drag). v1 ships SPOT-only; leveraged vehicles (Phase 13) are out of scope for the sentiment strategy's first iteration.

Scope is a PAPER-mode MVP intended as a 2–4 week observation window: let it accumulate trades, then decide keep / pivot-to-filter / drop.

---

## Architecture

```
┌─────────────────────┐    @Scheduled, every 5 min
│ SentimentScraper    │──────────────────────────────┐
└──────────┬──────────┘                              │
           │                                         │
     ┌─────┴────────┐                                ▼
     │              │                     ┌──────────────────────┐
     ▼              ▼                     │ sentiment_snapshots  │
┌──────────┐  ┌──────────────┐            │ (Postgres, JSONB)    │
│ Reddit   │  │ CryptoPanic  │            └──────────┬───────────┘
│ Client   │  │ Client       │                       │
│ (OkHttp, │  │ (RestClient) │                       │
│  OAuth2) │  │              │                       │
└────┬─────┘  └──────┬───────┘                       │
     │               │                               │
     └───────┬───────┘                               │
             ▼                                       │
  ┌───────────────────────┐                          │
  │ SentimentClassifier   │                          │
  │ (Claude Haiku batch,  │                          │
  │  per-post-ID cache,   │                          │
  │  daily-budget cap)    │                          │
  └───────────────────────┘                          │
                                                     │
                                                     ▼
                              ┌──────────────────────────────────────┐
                              │ SentimentService                     │
                              │   scoreFor(pair, interval)           │
                              │   → windowed mean from DB            │
                              │   60s in-memory cache per key        │
                              │   (FearGreedService pattern)         │
                              └──────────────────┬───────────────────┘
                                                 │
                                                 ▼
                              ┌──────────────────────────────────────┐
                              │ SentimentStrategy (@Component)       │
                              │   implements TradingStrategy         │
                              │   ignores BarSeries, reads interval  │
                              │   from series.getBar(0).getTimePeriod│
                              │   emits BUY/SELL/HOLD + confidence   │
                              └──────────────────────────────────────┘
                                                 │
                                                 ▼
                            SignalEngine → TradingLoop (unchanged)
```

**Key property:** fully additive. No changes to the other 13 strategies or the execution pipeline.

---

## Interval-aware sentiment windows

Sentiment is a continuous stream; the bot evaluates on 5 intervals. `SentimentService.scoreFor(pair, interval)` returns a rolling mean over a window matched to the interval:

| Interval | Window | Min sample size |
|---|---|---|
| 15m  | last 1 hour   | 20 |
| 1h   | last 4 hours  | 40 |
| 4h   | last 16 hours | 80 |
| 1d   | last 3 days   | 200 |
| 1w   | last 14 days  | 500 |

Below the minimum → `HOLD` with reason `"insufficient sentiment sample (n=X / min=Y)"`. Windows implemented as Postgres aggregate queries, cached in memory for 60s per `(pair, interval)` key.

**Signal logic (v1, thresholds config-driven):**

- `score > +0.35` AND `volume ≥ min` → **BUY**, confidence = `clamp(0..100, 50 + 50 × score)`
- `score < −0.35` AND `volume ≥ min` → **SELL**
- else → **HOLD**

The repurposed `Signal` indicator slots carry useful display values:

- `emaShort` → aggregate sentiment score (−1…+1)
- `emaLong` → volume (post count in window)
- `rsi` → sample size

These map straight into the existing `signal_logs` table without schema change.

---

## Reddit integration

- **Auth:** OAuth2 script-type app. Requires one-time manual setup at reddit.com/prefs/apps. Credentials injected via env vars `REDDIT_CLIENT_ID`, `REDDIT_CLIENT_SECRET`, `REDDIT_USERNAME`, `REDDIT_PASSWORD` (empty default in `application.yml` per project convention).
- **Subreddits (configurable):** `r/CryptoCurrency`, `r/Bitcoin`, `r/ethereum`, `r/solana`, `r/CryptoMarkets`.
- **Fetch cadence:** every 5 min, pull `new.json?limit=50` from each sub.
- **Filtering:** per-pair keyword match on title + selftext (e.g., BTC: "bitcoin", "btc", "$btc"). Posts with no pair match are stored with `pair=ALL` and excluded from pair-scoped aggregates.
- **Rate-limit respect:** Reddit's `X-Ratelimit-Remaining` header monitored; exponential backoff on 429.
- **Dedup:** `(source='REDDIT', external_id=post.id)` uniqueness prevents reclassification on each scrape.

## CryptoPanic integration

- **Auth:** `auth_token` query param. Env var `CRYPTOPANIC_AUTH_TOKEN`.
- **Endpoint:** `https://cryptopanic.com/api/v1/posts/?auth_token=X&currencies=BTC,ETH,SOL&kind=news&public=true`
- **Fetch cadence:** every 15 min → 96 calls/day × 3 pairs = 288 calls/day (well under the free-tier **500/day** ceiling noted in `10-news-sentiment.md`).
- **Scoring shortcut:** CryptoPanic already returns `votes: {positive, negative, important, liked, disliked}`. We derive a score directly from votes **without** LLM classification: `(positive − negative) / max(positive + negative, 1)` clamped to `[−1, +1]`. Saves LLM tokens — CryptoPanic's editorial + community vote signal is already curated.

## Claude Haiku classifier

- **Scope:** classifies only Reddit posts, not CryptoPanic.
- **Model:** `claude-haiku-4-5-20251001`.
- **Batching:** up to 20 posts per request. Single JSON-output prompt returning `[{id, score, reason}]` where `score ∈ [−1, 1]`.
- **Prompt caching:** system prompt (classification instructions + scoring rubric) cached — only the user-turn payload (20 post texts) varies. Effective per-batch cost ~$0.004.
- **Dedup cache:** `ConcurrentHashMap<RedditPostId, SentimentScore>` with bounded size (LRU eviction at 10k entries) — never re-classifies a post.

### Pre-filters to keep volume under target (~300 posts/day)

Reddit scraper applies these filters **before** sending to the classifier. A post must satisfy ALL of:

- `score ≥ 5` upvotes (filters out low-signal brand-new posts)
- `num_comments ≥ 3` (requires community engagement)
- `created_utc ≥ now − 24h` (skip stale backfills)
- **Top-N cap per subreddit per scrape:** only the 10 highest-scoring qualifying posts per sub per 5-min cycle. Absolute ceiling = 5 subs × 10 posts × 288 scrapes/day = 14,400 raw candidates, but dedup + these filters typically yield ~200–400 new classifications/day.

All filter thresholds are config-driven (`sentiment.reddit.filters.*`) so they can be tuned after the first observation window.

### Budget cap (hard €3/month ceiling)

Two layers, both enforced:

1. **Monthly hard cap:** `sentiment.classifier.monthly-budget-usd: 3.00` (~€2.80 — strictly under €3 even at worst-case EUR/USD). On breach, classifier no-ops until the 1st of the next month.
2. **Daily soft-cap:** `sentiment.classifier.daily-budget-usd: 0.12` (slight overage allowance within the monthly envelope). On daily breach, classifier sleeps until the next UTC day.

**Persistence:** a `llm_budget_daily` table (one row per UTC day: `day, usd_spent, posts_classified, last_updated`) created in the V10 migration. The classifier flushes its tally to this table after every batch — a crash or restart cannot reset the counter. On startup, the classifier sums the current month's rows and compares against the monthly cap before its first call.

**Telegram alert at 80% of monthly cap** via the existing `AlertService` so you know before classification goes dark. Alert fires once per month to avoid spam.

**Estimated monthly cost at ~300 posts/day target:** ~€1.70 (~$1.80). Worst-case at the ~500-posts/day original volume estimate: ~€2.80, still under the hard cap.

---

## Files to add / change

### Backend — Java

| # | File | Purpose |
|---|---|---|
| 1 | `config/SentimentConfig.java` | `@ConfigurationProperties(prefix="sentiment")` — enabled flag, API keys/tokens, subreddit list, scrape cadences, signal thresholds, per-interval window minutes, LLM budget cap |
| 2 | `model/enums/StrategyType.java` | **Edit** — add `SENTIMENT("Sentiment")` |
| 3 | `model/entity/SentimentSnapshot.java` | JPA entity with JSONB metadata column (follow `BotEvent` pattern) |
| 4 | `repository/SentimentSnapshotRepository.java` | Spring Data JPA; custom `@Query` for `meanScoreSince(pair, since)` returning score + volume + sample size |
| 5 | `market/RedditClient.java` | OkHttp, OAuth2 token refresh, per-subreddit polling, rate-limit handling, pair keyword matching |
| 6 | `market/CryptoPanicClient.java` | RestClient (FearGreedService pattern), parses `posts[].votes` into score |
| 7 | `service/SentimentClassifier.java` | Claude Haiku batch classification, prompt caching, per-post-ID memory cache, persistent daily+monthly budget accounting (reads/writes `llm_budget_daily` via `LlmBudgetRepository`) |
| 7a | `service/LlmBudgetTracker.java` | Wraps `llm_budget_daily` reads/writes; exposes `canSpend(usd)`, `record(usd, postCount)`, `monthlyTotal()`. Fires Telegram alert at 80% of monthly cap (one-shot per month) |
| 7b | `repository/LlmBudgetRepository.java` | Spring Data JPA over `llm_budget_daily`; custom `@Query` for `sumSpentForMonth(yearMonth)` |
| 8 | `service/SentimentScraper.java` | `@Scheduled(fixedDelayString = "#{${sentiment.scrape-interval-seconds:300} * 1000}")`, orchestrates Reddit+CryptoPanic, persists snapshots; graceful degradation per source |
| 9 | `service/SentimentService.java` | Public API. `scoreFor(pair, interval)` → windowed aggregate. Volatile-field 60s cache per key (FearGreedService pattern). `latestForPair(pair)` for REST endpoint |
| 10 | `strategy/impl/SentimentStrategy.java` | `@Component`, implements `TradingStrategy`. Derives interval from `series.getBar(0).getTimePeriod()`, calls `sentimentService`, emits Signal |
| 11 | `controller/DashboardController.java` | **Edit** — add `GET /api/market/sentiment?pair=BTC-EUR` returning `SentimentResponse` DTO (mirror `/api/market/fear-greed` at `controller/DashboardController.java:390-399`) |
| 12 | `model/dto/SentimentResponse.java` | Record: `pair, score, volume, sampleSize, sources[], capturedAt, stale` |

### Backend — resources

| # | File | Purpose |
|---|---|---|
| 13 | `db/migration/V10__add_sentiment_snapshots.sql` | Creates **two** tables: (a) `trading.sentiment_snapshots` — `id, pair, source, external_id, score DECIMAL(6,3), volume, sample_size, metadata JSONB, captured_at TIMESTAMP DEFAULT now()`, unique on `(source, external_id)`, index on `(pair, captured_at DESC)`; (b) `trading.llm_budget_daily` — `day DATE PRIMARY KEY, usd_spent DECIMAL(8,4) NOT NULL DEFAULT 0, posts_classified INT NOT NULL DEFAULT 0, last_updated TIMESTAMP NOT NULL DEFAULT now()` |
| 14 | `application.yml` | Add `sentiment:` block. **Default `enabled: false`** so it's opt-in per environment |

### Frontend — React

| # | File | Purpose |
|---|---|---|
| 15 | `src/api/sentiment.ts` | `fetchSentiment(pair)` — mirrors `api/fearGreed.ts` |
| 16 | `src/hooks/useSentiment.ts` | React Query hook, 2-min refetch, mirrors `hooks/useFearGreed.ts` |
| 17 | `src/components/SentimentWidget.tsx` | Header widget: score gauge (−1…+1, red→grey→green), sample size, source tooltip. Mirrors `FearGreedWidget.tsx` (84 lines) |
| 18 | `src/components/layout/Header.tsx` | **Edit** — mount `<SentimentWidget />` next to `<FearGreedWidget />` (around line 107) |

### Tests — Spock/Groovy

| # | File |
|---|---|
| 19 | `test/groovy/.../strategy/impl/SentimentStrategySpec.groovy` — BUY/SELL/HOLD matrix, insufficient-sample path, interval-from-series extraction |
| 20 | `test/groovy/.../service/SentimentServiceSpec.groovy` — windowed aggregation, 60s cache behaviour, graceful handling of empty window |
| 21 | `test/groovy/.../service/SentimentScraperSpec.groovy` — Reddit + CryptoPanic clients mocked, dedup behaviour, single-source-outage degradation |

### Contract + docs

| # | File |
|---|---|
| 22 | `API_CONTRACT.md` — add `SENTIMENT` to §2 enum list, `/api/market/sentiment` to §4.6, `SentimentResponse` to §5, polling cadence to §7 |
| 23 | Update root `CLAUDE.md` and backend `CLAUDE.md` strategy table (13 → 14) + new endpoint |

---

## Configuration reference

```yaml
sentiment:
  enabled: false                       # opt-in per environment
  scrape-interval-seconds: 300         # 5 min
  cryptopanic-interval-seconds: 900    # 15 min (288 calls/day, well under 500/day free tier)
  reddit:
    client-id:     ${REDDIT_CLIENT_ID:}
    client-secret: ${REDDIT_CLIENT_SECRET:}
    username:      ${REDDIT_USERNAME:}
    password:      ${REDDIT_PASSWORD:}
    user-agent:    "revolut-trading-bot/0.1 by stefo"
    subreddits:    [CryptoCurrency, Bitcoin, ethereum, solana, CryptoMarkets]
    filters:
      min-score: 5                   # min upvotes before classification
      min-comments: 3                # min comments before classification
      max-age-hours: 24              # skip posts older than this
      top-n-per-sub-per-scrape: 10   # hard cap per subreddit per 5-min cycle
  cryptopanic:
    auth-token: ${CRYPTOPANIC_AUTH_TOKEN:}
  classifier:
    anthropic-api-key: ${ANTHROPIC_API_KEY:}
    model: claude-haiku-4-5-20251001
    batch-size: 20
    monthly-budget-usd: 3.00         # ~€2.80 hard ceiling — strictly under €3
    daily-budget-usd: 0.12           # soft-cap; monthly cap always wins
    alert-at-monthly-pct: 80         # fires one Telegram alert per month when breached
  signal:
    buy-threshold: 0.35
    sell-threshold: -0.35
  windows:
    "15m":  { minutes: 60,    min-sample: 20 }
    "1h":   { minutes: 240,   min-sample: 40 }
    "4h":   { minutes: 960,   min-sample: 80 }
    "1d":   { minutes: 4320,  min-sample: 200 }
    "1w":   { minutes: 20160, min-sample: 500 }
```

---

## Design decisions locked in

- **Data sources:** Reddit + CryptoPanic. No Twitter (API $200/mo and aggressive anti-scrape, not worth it for v1).
- **Sentiment scoring:** Claude Haiku batch classification for Reddit; vote-derived score for CryptoPanic (no LLM call needed).
- **Integration shape:** new strategy #14 (standalone), not a filter-layer on top of existing strategies. Measurement-first approach.
- **Interval access in `SentimentStrategy`:** read from `series.getBar(0).getTimePeriod()` (ta4j returns a `Duration`). Maps 1:1 to our interval labels via a small `Duration → "15m"/"1h"/...` helper. **No change to `TradingStrategy` interface.** Fallback if ta4j's API turns out flaky: extract the median period across the first few bars, or add an overload to the interface as a last resort.
- **Vehicle scoping (Phase 13 compatibility):** The execution unit is now a quadruple `(pair, strategy, interval, vehicle)` with `vehicle ∈ {SPOT, LEV_3X, LEV_5X, LEV_10X}`. This plan ships **SPOT-only in v1** — no leveraged sentiment portfolios. `SentimentStrategy` just implements `TradingStrategy` like any other; if/when the execution loop fans strategies across vehicles, sentiment will be included automatically. Adds zero leverage-specific coupling.

---

## Risks & honest caveats

- **Empirical effectiveness is uncertain.** Academic evidence on sentiment → crypto price causality is mixed. 15m–1h sentiment often lags price; 1d+ is more plausibly predictive. This plan treats the build as a **measurement experiment**: ship to PAPER, observe 2–4 weeks, then decide keep / filter / drop.
- **External-source fragility.** Reddit rate-limit hit or OAuth token expired → scraper logs, backs off, CryptoPanic-only snapshots still populate. Strategy HOLDs if aggregate sample falls below min.
- **Reddit OAuth requires manual app registration** at reddit.com/prefs/apps — one-time 5-min step, free.
- **LLM cost control.** Hard monthly cap `monthly-budget-usd: 3.00` (~€2.80, strictly under user's €3/month ceiling). Soft daily cap `0.12`. Budget state persisted to `llm_budget_daily` table so crashes/restarts can't reset the tally. Classifier fails closed on breach; scraper continues to persist raw posts so they can be backfilled next month once budget resets. Telegram alert at 80% of monthly cap gives early warning before classification goes dark.
- **PAPER only.** No LIVE plumbing changes. Sentiment strategy must pass the same validation bar as any other before LIVE consideration (Phase 8).
- **Volume asymmetry across pairs.** BTC will dominate post volume; SOL likely has the smallest sample. Thresholds may need per-pair tuning after the first observation window.
- **Phase-2 opportunity:** if the standalone-strategy evidence is weak but sentiment still looks informative as a filter, fold into the `MarketContext`-multiplier design in `10-news-sentiment.md`. The `SentimentService.scoreFor(pair, interval)` built here is already the right shape for that.

---

## Effort estimate

| Area | Effort |
|---|---|
| Reddit client + OAuth + rate-limit handling | 1.0 day |
| CryptoPanic client + vote-based scoring | 0.25 day |
| Claude Haiku classifier + per-post cache + budget cap | 0.5 day |
| DB migration + entity + repository + aggregate query | 0.5 day |
| `SentimentScraper` scheduler + graceful degradation | 0.5 day |
| `SentimentService` with windowed aggregates + 60s cache | 0.5 day |
| `SentimentStrategy` + `StrategyType.SENTIMENT` wiring | 0.25 day |
| REST endpoint + `SentimentResponse` DTO + UI widget + hook | 0.5 day |
| Spock tests (3 spec files) | 0.75 day |
| `API_CONTRACT.md` + CLAUDE.md updates + integration smoke test | 0.5 day |
| **Total** | **~5 working days** |

---

## Verification

1. `mvn test` — all three new Spock specs green.
2. Drop + recreate local DB, boot app, confirm V10 applied and `trading.sentiment_snapshots` exists.
3. Set `sentiment.enabled=true` locally. Wait 5 min. Verify rows in `sentiment_snapshots` with plausible scores (−1…+1) and non-zero sample size. Confirm both REDDIT and CRYPTOPANIC sources present.
4. Hit `GET /api/strategies/SENTIMENT/signals?pair=BTC-EUR&interval=1h&limit=20` — expect BUY/SELL/HOLD mix with meaningful `reason` text.
5. Confirm `GET /api/stats/all-triples` returns **+15 SPOT rows** (one new strategy × 3 pairs × 5 intervals) vs the pre-change SPOT baseline. (Exact absolute count depends on whether Phase 13 leverage is active.)
6. UI smoke: dashboard renders new strategy column/row without layout breakage; `SentimentWidget` populates in Header.
7. Failure injection: blank out `REDDIT_CLIENT_ID`, confirm scraper logs warning and CryptoPanic-only snapshots still land; strategy still signals when aggregate crosses min sample.
8. Kill-switch: set `sentiment.enabled=false`, confirm scraper stops and strategy returns HOLD with reason `"sentiment feature disabled"`, no new DB rows.
9. Budget cap — daily: set `daily-budget-usd: 0.001`, confirm classifier no-ops after first batch with a `BUDGET_EXCEEDED` log, scraper continues to write raw snapshots, `llm_budget_daily` row has a non-zero `usd_spent`.
9a. Budget cap — monthly: set `monthly-budget-usd: 0.001`, confirm the monthly breach is detected on startup (before any API call) by summing the current month's rows; classifier stays dark for the rest of the month; Telegram alert fires once at the 80% threshold crossing.
9b. Budget cap — restart persistence: run classifier to ~80% of daily cap, kill the process, restart, confirm the loaded counter reflects the pre-crash spend (i.e. cap is not reset by restart).
10. Let it run for 2–4 weeks in PAPER. Compare `SENTIMENT` row in `/api/stats/all-triples` (win rate, expectancy, net PnL) against the other 13 strategies at the end of the window before deciding keep / filter / drop.

---

## Follow-ups

- If the standalone-strategy experiment shows weak but non-zero edge: fold into `10-news-sentiment.md`'s multiplier design as Phase 2. Reuse `SentimentService` unchanged.
- If Twitter coverage becomes valuable later: swap Reddit+classifier for LunarCrush ($29/mo) — same `SentimentSnapshot` shape, drop the Haiku dependency.
- Historical backtesting on sentiment data requires a paid CryptoPanic plan for historical news; Reddit has no historical search in the free API. Out of scope for v1.
