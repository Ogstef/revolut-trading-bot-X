# Phase 14 — Deploy: 3 Sentiment Strategies + Scraper Microservice → VPS

**Type:** Production rollout for the Phase 14 work that is currently running locally only.
**Effort:** ~1.5 hours of clock time (most of it waiting for builds + first scraper cycles).
**Risk:** Medium — the backend deploy auto-applies V10 Flyway migration to prod DB; scraper is a brand-new module the VPS hasn't seen before.
**Cost impact:** +€0–€3/month (Claude Haiku, hard-capped). Reddit + scraper hosting are free.

---

## Context

Phase 14 sentiment work is fully built and validated locally:

- **Backend:** 3 new strategies (`REDDIT_SENTIMENT`, `CRYPTOPANIC_SENTIMENT`, `COMBINED_SENTIMENT`), ingest controller + auth filter, classifier wrapping Claude Haiku, V10 Flyway migration adding `sentiment_snapshots` + `llm_budget_daily` tables. 258 Spock tests green, end-to-end verified — the 1d Reddit strategy is currently emitting BUY signals (`score=+0.220, n=52`).
- **Frontend:** `SentimentWidget` in header, three new strategies in `KNOWN_STRATEGIES`, typed DTOs for the new endpoints.
- **Scraper:** New `scrapers/sentiment-scraper/` Python microservice, 18 pytest passing, runs as a single Docker container with an internal APScheduler loop posting to the Java backend's ingest endpoint.

Locally tuned during the first 24h observation window (committed in `application.yml`):
- Window min-samples lowered ~10× (real Reddit volume is much lower than the original draft assumed).
- Buy/sell thresholds lowered from `±0.35` → `±0.20` (`±0.25` for COMBINED).
- Reddit pre-filters relaxed: `min-score: 5→2`, `min-comments: 3→0`, `max-age-hours: 24→72`.
- Reddit scraper switched from `old.reddit.com` → `www.reddit.com` + browser-like User-Agent (the `old.` path 403's after a few hours).

After this phase: **16 strategies × 3 pairs × 5 intervals = 240 SPOT portfolios on the VPS** (was 195). Like the LEV_*X rollout, the new strategies start with empty baselines — the Leaderboard heatmap should treat them as NEW for ~2–4 weeks.

---

## VPS prerequisites

These are assumed already in place from earlier deploys:

- [x] Backend repo at `/opt/revolut-trading-bot` with `deployed-version` branch tracked
- [x] systemd unit `revolut-trading-bot.service` with `EnvironmentFile=/etc/revolut-trading-bot/env`
- [x] PostgreSQL 16 in Docker container `trading-db`
- [x] Nginx fronting the UI from `/var/www/revolut-trading-bot-ui`
- [x] Docker installed (used for `trading-db`)
- [x] `gh` / git push access from local Mac

New for Phase 14:

- [ ] Docker daemon must be reachable by `systemd` (already true since `trading-db` runs in Docker)
- [ ] Two new env vars on the backend side: `SENTIMENT_INGEST_TOKEN`, `ANTHROPIC_API_KEY`
- [ ] One new systemd unit + env file for the scraper
- [ ] One Docker image built on the VPS for the scraper

---

## Step-by-step plan

### Step 1 — Local commits (10 min)

Group the unstaged work into three commits to keep the history scannable.

```bash
cd /Users/stefanosgeorgiou/IdeaProjects/revolut-trading-bot-X
git status        # confirm what you have

# 1) backend + UI + docs (one feature, three modules)
git add revolut-trading-bot/src \
        revolut-trading-bot/CLAUDE.md \
        revolut-trading-bot-ui/src \
        revolut-trading-bot-ui/CLAUDE.md \
        API_CONTRACT.md \
        CLAUDE.md
git commit -m "Add 3 sentiment strategies + ingest pipeline (Phase 14)"

# 2) scraper microservice
git add scrapers/
git commit -m "Add sentiment-scraper Python microservice"

# 3) plan + future-plans entry
git add "future plans/" .claude .mcp.json
git commit -m "Add Phase 14 sentiment plan + deploy plan"
```

**Don't push yet** — VPS env vars need to be set first or the backend will fail health-check on first start.

### Step 2 — VPS secrets (manual, 10 min — see "Manual secrets setup" section below)

Done out-of-band by SSHing into the VPS. Follow that section first.

### Step 3 — Backend + UI deploy (existing pipeline, 5 min)

```bash
# From local Mac, push develop and merge to deployed-version
git push origin develop

# Use the existing /commit-and-deploy or /deploy-all skill
/deploy-all
```

That:
- Merges `develop` → `deployed-version` for both repos
- Pushes both
- VPS pulls each repo's `deployed-version`, rebuilds, restarts systemd unit
- **Flyway auto-applies V10** on Spring Boot startup — watch backend log for:
  ```
  Migrating schema "trading" to version "10 - add sentiment and budget tables"
  ```

### Step 4 — Scraper deploy (one-time setup, 30 min)

The scraper has never been deployed before — this is a manual sequence that automates after this run.

```bash
ssh stef@204.168.228.158
cd /opt/revolut-trading-bot
git pull        # pulls scrapers/ folder

# Build the Docker image
cd scrapers/sentiment-scraper
sudo docker build -t sentiment-scraper:latest .

# Install the env file (separate from backend's, but tokens must match!)
sudo mkdir -p /etc/sentiment-scraper
sudo cp .env.example /etc/sentiment-scraper/env
sudo chmod 600 /etc/sentiment-scraper/env
sudo nano /etc/sentiment-scraper/env
```

Fill in `/etc/sentiment-scraper/env`:

```
SCRAPER_INGEST_TOKEN=<same value as the backend's SENTIMENT_INGEST_TOKEN>
SCRAPER_INGEST_BASE_URL=http://localhost:8089
SCRAPER_REDDIT_SUBREDDITS=Bitcoin,ethereum,solana,CryptoMarkets
SCRAPER_CRYPTOPANIC_CURRENCIES=
```

Install the systemd unit:

```bash
sudo cp systemd/sentiment-scraper.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now sentiment-scraper
sudo systemctl status sentiment-scraper       # should be active (running)
```

Watch the first cycle:

```bash
sudo journalctl -u sentiment-scraper -f --since '1 minute ago'
```

You should see within 2 minutes:
```
[reddit] /r/Bitcoin: 50 posts scraped
[reddit] ingested: received=N accepted=M deduped=0 filtered=K classified=M
```

### Step 5 — Verification (15 min)

From your local Mac:

```bash
# Backend up + Phase 14 in production
curl -s http://204.168.228.158:8089/api/status | python3 -m json.tool

# 240 SPOT triples (was 195)
curl -s http://204.168.228.158:8089/api/stats/all-triples | python3 -c "import sys,json; print(len(json.load(sys.stdin)))"

# Sentiment endpoint should return JSON (score may be null until scraper has run a few cycles)
curl -s 'http://204.168.228.158:8089/api/market/sentiment?pair=BTC-EUR&source=COMBINED' | python3 -m json.tool

# DB check via the postgres-vps MCP (or SSH and docker exec)
# - sentiment_snapshots accumulating
# - llm_budget_daily today's row exists with usd_spent > 0
```

Frontend: open the UI URL, verify the Sentiment widget renders in the header. Three new strategy cards on Overview.

### Step 6 — Monitoring (first 24h)

- **Telegram alert at 80% of monthly cap.** Will fire once if Haiku spend hits $2.40 in a month — this gives you ~1 day's notice before the cap hits.
- **Reddit anti-bot.** Locally we needed 2 fixes (UA rotation, www subdomain). VPS will hit the same wall eventually. When `ingested: received=0` for 30+ minutes, escalate to Playwright.
- **Budget runaway.** Spend > $0.20/day for several days = trending over cap. Lower `reddit-filters.min-score` further (or raise it!).

---

## Manual secrets setup (do this BEFORE Step 3)

You need to add **two new env vars** to the backend's existing systemd EnvironmentFile, and create a NEW env file for the scraper. Both pieces of the same shared-secret pair — they MUST match byte-for-byte.

### A — Pick the shared token

Either reuse the local one (printed below) or generate a fresh one (recommended, since the local one has been on your Mac for a couple days):

```bash
# Generate a fresh 64-char token
openssl rand -hex 32
```

Copy that string — you'll paste it twice (once into the backend env, once into the scraper env).

Local current value (for reference if you want to keep using it):

```
935fa12d839fb762b4af17e0fe3b16e43c6768438782308af0c8d9f5772862a5
```

### B — SSH in

```bash
ssh stef@204.168.228.158
```

### C — Add the two new env vars to the backend env file

Find the file your backend systemd unit references — likely `/etc/revolut-trading-bot/env`. Confirm:

```bash
sudo systemctl cat revolut-trading-bot | grep -i environmentfile
```

Append the two new vars:

```bash
sudo nano /etc/revolut-trading-bot/env
```

Add these lines at the bottom:

```
SENTIMENT_INGEST_TOKEN=<the 64-char token>
ANTHROPIC_API_KEY=<paste your sk-ant-api03-... key from console.anthropic.com>
```

Save and exit (`Ctrl+O`, `Enter`, `Ctrl+X` in nano).

Reload systemd so the new env is picked up next restart:

```bash
sudo systemctl daemon-reload
```

The next backend restart (Step 3) will pick these up automatically.

### D — Verify the env file is readable

```bash
sudo cat /etc/revolut-trading-bot/env | grep -E '(SENTIMENT_INGEST|ANTHROPIC_API|TELEGRAM)' | sed 's/=.*/=<set>/'
```

You should see all three lines marked `=<set>` with no `=<empty>` — confirms the values were saved.

### E — Don't forget step 4's `/etc/sentiment-scraper/env`

Re-paste the SAME `SENTIMENT_INGEST_TOKEN` value into `/etc/sentiment-scraper/env` as `SCRAPER_INGEST_TOKEN` (note the rename — Java side is `SENTIMENT_INGEST_TOKEN`, scraper side is `SCRAPER_INGEST_TOKEN`).

If they differ, the scraper will get 401 every cycle.

---

## Rollback plan

If anything goes sideways:

1. **Disable the pipeline at the kill switch** (no rollback of code needed):
   ```bash
   ssh stef@204.168.228.158
   sudo nano /etc/revolut-trading-bot/env
   # Add: SENTIMENT_ENABLED=false (overrides application.yml's true)
   sudo systemctl restart revolut-trading-bot
   ```
   This stops ingest (returns 503), all sentiment strategies HOLD. Existing 13 TA strategies keep trading normally.

2. **Stop the scraper** if it's misbehaving:
   ```bash
   sudo systemctl stop sentiment-scraper
   ```

3. **Full backend rollback** (revert deployed-version branch):
   ```bash
   cd /opt/revolut-trading-bot
   sudo git reset --hard <previous-commit-sha>
   sudo systemctl restart revolut-trading-bot
   ```

V10 schema is additive (new tables only) — no rollback needed for the migration. The new tables stay and remain empty if rolled back.

---

## Verification checklist

After Step 5:

- [ ] `curl /api/status` returns `running: true`
- [ ] `curl /api/stats/all-triples | jq length` returns 240
- [ ] `curl /api/market/sentiment?pair=BTC-EUR&source=COMBINED` returns JSON (may have `score: null` initially)
- [ ] `sudo journalctl -u revolut-trading-bot --since '5 minutes ago' | grep -i flyway` shows V10 applied
- [ ] `sudo journalctl -u sentiment-scraper --since '5 minutes ago' | grep -i ingested` shows at least one `accepted=N>0` line
- [ ] DB: `SELECT * FROM trading.llm_budget_daily ORDER BY day DESC LIMIT 1` has today's row with `usd_spent > 0`
- [ ] DB: `SELECT pair, source, COUNT(*) FROM trading.sentiment_snapshots GROUP BY pair, source` returns at least 1 row per active pair
- [ ] Frontend: Sentiment widget renders in header (refresh the page; might be `—` until first scraper cycle)
- [ ] Frontend: Overview page shows 3 new strategy cards (REDDIT_SENTIMENT, CRYPTOPANIC_SENTIMENT, COMBINED_SENTIMENT)

After 24h:

- [ ] `usd_spent` trending well below $0.10/day (if higher, raise `reddit-filters.min-score` again)
- [ ] At least the 1d-interval REDDIT_SENTIMENT strategy has emitted some non-HOLD signals
- [ ] Telegram alert hasn't fired (would mean we're at 80% of monthly cap — early warning)
- [ ] No journal errors from `sentiment-scraper` other than expected Reddit 403 rotations

---

## Known caveats

- **CryptoPanic disabled.** Site is a Vue SPA that BS4 can't parse. Three options for follow-up:
  1. Add Playwright to the scraper (proper fix, +500 MB Docker image)
  2. Find an alternative news source (RSS feeds, NewsAPI, etc.)
  3. Drop it entirely; rely on Reddit + the existing `/api/market/fear-greed` for sentiment
- **CRYPTOPANIC_SENTIMENT will permanently HOLD** until one of the above is done. COMBINED_SENTIMENT degrades to Reddit-only via the sample-weighted blend (CP contributes 0 weight when its volume is 0).
- **Reddit anti-bot is adversarial.** The current `www.reddit.com` + browser-UA fix may need re-iteration in days/weeks. Plan to monitor the journal weekly.
- **Hard cap is monthly.** If we hit it mid-month the classifier sleeps until UTC day 1 of the next month. CryptoPanic side keeps working (when re-enabled).
- **VPS-side dedup cache** lives in a Docker volume (`sentiment-scraper-dedup`) — survives container restarts but NOT volume deletion. First cycle on VPS will be a one-time burst (~$0.05).
- **Fresh baselines.** As with LEV_*X rollout (2026-04-24), the 3 new strategies start at zero. Treat them as NEW in the Leaderboard for ~2–4 weeks.

---

## Files this plan affects

### Already in the local repo (waiting for commit)

- `revolut-trading-bot/src/main/resources/application.yml` — `sentiment:` block, tuned filters
- `revolut-trading-bot/src/main/resources/db/migration/V10__add_sentiment_and_budget_tables.sql`
- `revolut-trading-bot/src/main/java/.../config/SentimentConfig.java`
- `revolut-trading-bot/src/main/java/.../config/security/IngestTokenFilter.java`
- `revolut-trading-bot/src/main/java/.../controller/SentimentIngestController.java`
- `revolut-trading-bot/src/main/java/.../market/{AnthropicClient,CryptoPanicClient}.java`
- `revolut-trading-bot/src/main/java/.../service/{SentimentService,SentimentClassifier,RedditIngestService,CryptoPanicIngestService,LlmBudgetTracker}.java`
- `revolut-trading-bot/src/main/java/.../strategy/impl/{Reddit,CryptoPanic,Combined}SentimentStrategy.java`
- `revolut-trading-bot/src/main/java/.../strategy/impl/{SentimentSignalBuilder,SentimentStrategyHelpers,BarSeriesIntervalDetector}.java`
- `revolut-trading-bot/src/main/java/.../model/{enums,entity,dto}/...` — many new DTOs/entities
- `revolut-trading-bot/src/main/java/.../repository/{SentimentSnapshot,LlmBudget}Repository.java`
- `revolut-trading-bot/src/test/groovy/.../**/Spec.groovy` — 35 new tests
- `revolut-trading-bot-ui/src/api/{client.ts,sentiment.ts}` + `hooks/useSentiment.ts` + `components/SentimentWidget.tsx`
- `revolut-trading-bot-ui/src/components/layout/Header.tsx` (mount point) + `utils/strategyMeta.ts` (3 entries)
- `scrapers/sentiment-scraper/**` — entire new module
- `API_CONTRACT.md`, `CLAUDE.md` (root + backend), `revolut-trading-bot-ui/CLAUDE.md`

### To be created on VPS only

- `/etc/sentiment-scraper/env` (env file with `SCRAPER_*` values)
- `/etc/systemd/system/sentiment-scraper.service` (copied from `scrapers/sentiment-scraper/systemd/`)
- Docker image `sentiment-scraper:latest` (built locally on VPS)
- Two lines added to existing `/etc/revolut-trading-bot/env` (`SENTIMENT_INGEST_TOKEN`, `ANTHROPIC_API_KEY`)
