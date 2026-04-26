---
name: scraper-logs
description: Tail or fetch recent logs from the sentiment-scraper systemd service on the VPS (204.168.228.158). Shows the last N lines, a time-windowed slice, follows live, filters to errors, or summarizes ingest results — pick the variant that matches the user's request.
---

# View VPS scraper logs

Pick the variant that matches the user's intent. Default (no qualifier) is the last 50 lines. The scraper service is `sentiment-scraper` (Docker container managed by systemd) on the VPS.

## Last 50 lines (default)

```bash
ssh stef@204.168.228.158 'sudo journalctl -u sentiment-scraper -n 50 --no-pager'
```

## Time-windowed (e.g. last 2 hours)

```bash
ssh stef@204.168.228.158 'sudo journalctl -u sentiment-scraper --since "2 hours ago" --no-pager'
```

For wide windows pipe through `tail` or filter — the scraper logs ~30 lines per 5-min cycle.

## Follow live

```bash
ssh stef@204.168.228.158 'sudo journalctl -u sentiment-scraper -f'
```

Use only when the user explicitly says "tail live", "watch", "follow". Blocks until interrupted.

## Just the cycle results (most common — "is it working?")

Filters to the human-readable summary lines: posts scraped per sub + the final `received/accepted/classified` line per cycle.

```bash
ssh stef@204.168.228.158 'sudo journalctl -u sentiment-scraper --since "30 minutes ago" --no-pager | grep -E "(posts scraped|ingested:)"'
```

Sample healthy output:
```
[reddit] /r/Bitcoin: 50 posts scraped
[reddit] /r/ethereum: 50 posts scraped
[reddit] /r/solana: 50 posts scraped
[reddit] /r/CryptoMarkets: 50 posts scraped
[reddit] ingested: received=200 accepted=12 deduped=38 filtered=150 classified=12
```

## Filter to errors + warnings

```bash
ssh stef@204.168.228.158 'sudo journalctl -u sentiment-scraper --since "2 hours ago" --no-pager | grep -E "(ERROR|WARNING|Exception|failed|refused|403|401|429)" | tail -30'
```

Common patterns to call out if found:
- **`403 Blocked`** on every Reddit URL → Reddit anti-bot flagged the VPS IP. Means no fresh data is coming in. May need to switch endpoint.
- **`Connection refused` to `localhost:8089`** → backend down OR scraper container missing `--network host`.
- **`returned 401`** → token mismatch between scraper env and backend env.
- **`returned 503`** → `sentiment.enabled: false` on backend.
- **`returned 413`** → batch too large. `sentiment.ingest.max-batch-size` exceeded.

## Today's classifier spend (DB, not journal)

The journal only shows ingest counts; for actual Haiku spend hit the DB:

```bash
ssh stef@204.168.228.158 'sudo docker exec revolut-postgres psql -U trading_bot -d trading_bot -c "SELECT * FROM trading.llm_budget_daily ORDER BY day DESC LIMIT 7;"'
```

`usd_spent` should trend below $0.10/day. The hard cap is $3.00/month (`sentiment.classifier.monthly-budget-usd`).

## Sentiment row counts (DB)

```bash
ssh stef@204.168.228.158 'sudo docker exec revolut-postgres psql -U trading_bot -d trading_bot -c "SELECT pair, source, COUNT(*) AS n, ROUND(AVG(score)::numeric,3) AS avg, MAX(captured_at) AS latest FROM trading.sentiment_snapshots GROUP BY pair, source ORDER BY pair, source;"'
```

Confirms data is flowing in per pair × source. After a clean run, expect at least BTC-EUR / ETH-EUR / SOL-EUR / ALL each with rows from the REDDIT source.
