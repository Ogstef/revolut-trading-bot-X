---
name: deploy-scraper
description: Redeploy the sentiment-scraper microservice to the VPS (204.168.228.158). Pulls latest main from Ogstef/reddit-crypto-scraper, rebuilds the Docker image, reinstalls the systemd unit if it changed, and restarts the service. Run this after pushing changes to the scraper's main branch.
---

# Deploy scraper to VPS

Use when the user asks to "deploy the scraper", "push scraper to prod", "update the VPS scraper", or similar. The scraper lives in its own repo (`Ogstef/reddit-crypto-scraper`) checked out on the VPS at `/opt/reddit-crypto-scraper`. The image runs as a Docker container managed by systemd unit `sentiment-scraper.service`.

Assumes the user has already committed + pushed their changes to the `main` branch of `Ogstef/reddit-crypto-scraper`.

## Preconditions

Local SSH agent has the ed25519 key loaded (the VPS pulls the private repo via agent forwarding):
```bash
ssh-add -l | grep -q ed25519 || ssh-add ~/.ssh/id_ed25519
```

## Detecting whether a redeploy is needed

Cheap check — does the VPS have new commits to pull?

```bash
ssh stef@204.168.228.158 '
  cd /opt/reddit-crypto-scraper
  git fetch origin main --quiet
  local_sha=$(git rev-parse HEAD)
  remote_sha=$(git rev-parse origin/main)
  if [ "$local_sha" = "$remote_sha" ]; then echo "up-to-date"; else echo "needs-deploy"; fi
'
```

If the output is `up-to-date`, tell the user there is nothing to deploy and stop. Only run the deploy steps below when the check returns `needs-deploy`.

## Steps

Single command — uses `set -e` so any failure aborts before the restart. The conditional on the systemd unit avoids reloading systemd when the unit hasn't changed (saves a few seconds and is gentler on the running service).

```bash
ssh -A stef@204.168.228.158 '
  set -e
  cd /opt/reddit-crypto-scraper

  # Capture old systemd-unit hash before pulling
  old_unit_sha=$(sha256sum sentiment-scraper/systemd/sentiment-scraper.service 2>/dev/null | awk "{print \$1}")
  git pull --ff-only

  # Always rebuild the image — code changes are the most common case
  cd sentiment-scraper && sudo docker build -t sentiment-scraper:latest . | tail -3

  # Only re-copy the systemd unit if its hash changed
  new_unit_sha=$(sha256sum systemd/sentiment-scraper.service | awk "{print \$1}")
  if [ "$old_unit_sha" != "$new_unit_sha" ]; then
    echo "[deploy-scraper] systemd unit changed — reinstalling"
    sudo cp systemd/sentiment-scraper.service /etc/systemd/system/
    sudo systemctl daemon-reload
  fi

  sudo systemctl restart sentiment-scraper
  sleep 4
  sudo systemctl is-active sentiment-scraper
'
```

Expected output ends with `active`. The Docker build takes ~10-20s if the requirements layer is cached, ~2 min on a clean rebuild.

## Verify

After the restart, confirm the scraper is reaching the backend on the next cycle. The Reddit job runs immediately on startup, so logs should show an ingest attempt within ~30s:

```bash
ssh stef@204.168.228.158 'sudo journalctl -u sentiment-scraper --since "1 minute ago" --no-pager | grep -E "(scraped|ingested|ERROR)" | tail -10'
```

Look for a line like:
```
[reddit] ingested: received=N accepted=M deduped=K filtered=F classified=M
```

`accepted=0 classified=0` is fine right after a deploy if all current posts are dedup'd — the next 5-min cycle will bring fresh content.

## If the restart fails

Tail the service:

```bash
ssh stef@204.168.228.158 'sudo journalctl -u sentiment-scraper -n 50 --no-pager'
```

Common issues:
- **`Connection refused` to `localhost:8089`** — the systemd unit needs `--network host` in the docker run line. Check `/etc/systemd/system/sentiment-scraper.service`.
- **`401 Unauthorized` on every POST** — `SCRAPER_INGEST_TOKEN` (in `/etc/sentiment-scraper/env`) and `SENTIMENT_INGEST_TOKEN` (in `/etc/revolut-trading-bot.env`) don't match. Compare via `sha256sum`:
  ```bash
  ssh stef@204.168.228.158 '
    sudo grep "^SCRAPER_INGEST_TOKEN=" /etc/sentiment-scraper/env | cut -d= -f2- | sha256sum
    sudo grep "^SENTIMENT_INGEST_TOKEN=" /etc/revolut-trading-bot.env | cut -d= -f2- | sha256sum
  '
  ```
- **`403 Blocked` from all subreddits** — Reddit's anti-bot flagged the VPS IP. Fix is in code, see `scrapers/sentiment-scraper/src/sentiment_scraper/reddit/scraper.py`.
- **`No such image: sentiment-scraper:latest`** — `docker build` didn't run or failed. Re-run the deploy step.
