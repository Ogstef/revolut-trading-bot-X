# A1 — PostgreSQL MCP Server [P0]

**Type:** Development-time MCP
**Effort:** 30 minutes
**Value:** High
**Dependencies:** None

## Purpose

Give Claude Code direct SQL access to the `trading_bot` database during coding sessions. Today, inspecting trade performance or signal accuracy requires writing new Spring endpoints or asking Claude to read JSON blobs. With this MCP installed, Claude can run ad-hoc SQL against the 72 virtual portfolios.

## Server

`@modelcontextprotocol/server-postgres` — official PostgreSQL MCP server, enforces read-only transactions by default.

- GitHub: https://github.com/modelcontextprotocol/servers/tree/main/src/postgres
- Transport: stdio
- Language: TypeScript/Node.js

## Example Queries Unlocked

```sql
-- Win rate per (pair, strategy, interval)
SELECT pair, strategy_name, interval,
       COUNT(*) AS trades,
       COUNT(CASE WHEN pnl > 0 THEN 1 END)::float / COUNT(*) AS win_rate,
       AVG(pnl) AS avg_pnl
FROM trading.trades
WHERE pnl IS NOT NULL
GROUP BY pair, strategy_name, interval
ORDER BY win_rate DESC;

-- Compare same strategy across intervals (does 1h outperform 15m?)
SELECT interval, SUM(pnl) AS total_pnl, COUNT(*) AS trades,
       AVG(pnl_pct) AS avg_pct_gain
FROM trading.trades
WHERE strategy_name = 'ICHIMOKU' AND pair = 'BTC-EUR'
GROUP BY interval;

-- Signal density per interval
SELECT interval, signal_type, COUNT(*)
FROM trading.signal_logs
WHERE created_at > NOW() - INTERVAL '7 days'
GROUP BY interval, signal_type
ORDER BY interval, signal_type;

-- Signals that turned into winning trades (conversion analysis)
SELECT s.strategy_name, s.pair, s.interval,
       COUNT(DISTINCT s.id) AS buy_signals,
       COUNT(DISTINCT t.id) AS opened_positions,
       COUNT(DISTINCT CASE WHEN t.pnl > 0 THEN t.id END) AS winners
FROM trading.signal_logs s
LEFT JOIN trading.trades t ON t.pair = s.pair
     AND t.strategy_name = s.strategy_name
     AND t.interval = s.interval
     AND t.executed_at >= s.created_at
     AND t.executed_at < s.created_at + INTERVAL '10 minutes'
WHERE s.signal_type = 'BUY'
GROUP BY s.strategy_name, s.pair, s.interval;

-- Drawdown analysis
WITH equity AS (
  SELECT strategy_name, pair, interval, closed_at,
         SUM(pnl) OVER (PARTITION BY strategy_name, pair, interval
                        ORDER BY closed_at) AS cumulative_pnl
  FROM trading.trades WHERE pnl IS NOT NULL
)
SELECT strategy_name, pair, interval,
       MAX(cumulative_pnl) - MIN(cumulative_pnl) AS range,
       MIN(cumulative_pnl - MAX(cumulative_pnl)
           OVER (PARTITION BY strategy_name, pair, interval
                 ORDER BY closed_at)) AS max_drawdown
FROM equity
GROUP BY strategy_name, pair, interval
ORDER BY max_drawdown;
```

## Configuration

Modify `/Users/stefanosgeorgiou/IdeaProjects/revolut-trading-bot-X/.claude/settings.local.json`:

```json
{
  "permissions": {
    "allow": [
      "Bash(grep -E \"\\\\.\\(java|properties|yml\\)$\")"
    ]
  },
  "mcpServers": {
    "postgres": {
      "command": "npx",
      "args": [
        "-y",
        "@modelcontextprotocol/server-postgres",
        "postgresql://trading_bot:secret@localhost:5432/trading_bot"
      ]
    }
  }
}
```

## Safety

- The official PostgreSQL MCP server sets `SET TRANSACTION READ ONLY` on every query — destructive DDL/DML is rejected by the database layer, not just the client
- Connection string contains local dev credentials only — not committed secrets
- If anything in the config needs to point at production, use an environment variable reference

## Verification

1. Restart Claude Code session after editing `settings.local.json`
2. Ask Claude to "list tables in the trading schema" — should see `candlesticks`, `positions`, `signal_logs`, `trades`
3. Run a known query (e.g. `SELECT COUNT(*) FROM trading.trades`) — compare against `/api/stats` response
4. Attempt a `DELETE` — must fail with "cannot execute DELETE in a read-only transaction"

## Follow-ups

- Once comfortable, consider adding a second MCP entry pointing at a staging DB if one exists
- Pair with the Custom Backtest MCP ([`09-custom-backtest-mcp.md`](./09-custom-backtest-mcp.md)) for a full data + compute toolkit
