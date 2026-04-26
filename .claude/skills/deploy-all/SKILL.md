---
name: deploy-all
description: Redeploy backend, UI, and (if changed) the sentiment-scraper to the VPS in sequence. Equivalent to invoking deploy-backend then deploy-ui then conditionally deploy-scraper. Use when one or more of the three repos have updates ready to ship.
---

# Deploy backend + UI + scraper

Run the three deploy skills in order. Backend goes first because the UI depends on the API contract and the scraper depends on the ingest endpoint; if backend deploy fails, stop before touching anything else.

## Steps

1. **Backend** — Invoke the `deploy-backend` skill and wait for success (`active` in its output). Stop everything if it fails.

2. **UI** — Only if the backend is healthy, invoke the `deploy-ui` skill.

3. **Scraper (conditional)** — Check whether `Ogstef/reddit-crypto-scraper` `main` is ahead of what the VPS has. If yes, invoke `deploy-scraper`. If the VPS is already up to date, skip and report "scraper unchanged — skipped".

   The check is a single ssh command:

   ```bash
   ssh stef@204.168.228.158 '
     cd /opt/reddit-crypto-scraper
     git fetch origin main --quiet
     local_sha=$(git rev-parse HEAD)
     remote_sha=$(git rev-parse origin/main)
     if [ "$local_sha" = "$remote_sha" ]; then echo "up-to-date"; else echo "needs-deploy"; fi
   '
   ```

   - Output `up-to-date` → skip the scraper deploy. Tell the user "scraper unchanged — skipped".
   - Output `needs-deploy` → invoke the `deploy-scraper` skill.

4. **Combined summary** — report:
   - Backend service status (`active`)
   - UI bundle filename
   - Scraper status: either "skipped (up-to-date)" or `active` with the latest ingest line
   - Result of `GET /actuator/health`

## Quick health checks after all three

```bash
curl -s http://204.168.228.158:8080/actuator/health
curl -s http://204.168.228.158:8080/api/status
curl -s http://204.168.228.158:8080/ | grep -o 'assets/index-[^"]*\.js'
ssh stef@204.168.228.158 'sudo systemctl is-active sentiment-scraper && sudo journalctl -u sentiment-scraper --since "1 minute ago" --no-pager | grep ingested | tail -1'
```
