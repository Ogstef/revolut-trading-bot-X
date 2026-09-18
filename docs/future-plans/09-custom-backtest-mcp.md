# C3 — Custom Backtest MCP Wrapper [P1]

**Type:** Development-time MCP
**Effort:** 1 day
**Value:** High — makes A3/B2/C1/C2 accessible to Claude Code as conversational tools
**Dependencies:** A3 (Backtest engine), B2 (Risk analytics), C1 (Parameter optimizer), C2 (Correlation service)

## Purpose

The REST APIs built in A3, B2, C1, C2 are powerful but require someone to remember URL paths, JSON bodies, and field names. The Custom Backtest MCP wraps them as **Claude Code tools** so conversations like this become natural:

> **User:** "Which strategy works best for ETH-EUR on 1h over the last month?"
>
> **Claude:** *(calls `compare_strategies` tool automatically)* "ICHIMOKU leads with 2.1 Sharpe and 61% win rate. TRIPLE_EMA is second at 1.8 Sharpe..."

Without the MCP, the developer would have to manually `curl` endpoints or build a test controller.

## Implementation Language

**Recommendation: Node.js / TypeScript** (Python works too).

Reasons:
- The official MCP SDK is TypeScript-first (`@modelcontextprotocol/sdk`)
- Spawning is simpler (`npx`) with no virtualenv concerns
- Stdio transport is trivial in Node

## Project Structure

```
revolut-trading-bot-X/
├── mcp-servers/
│   └── backtest/
│       ├── package.json
│       ├── tsconfig.json
│       ├── src/
│       │   ├── index.ts          # MCP server entry
│       │   ├── client.ts         # HTTP client for Spring Boot API
│       │   └── tools/
│       │       ├── run_backtest.ts
│       │       ├── compare_strategies.ts
│       │       ├── compare_intervals.ts
│       │       ├── optimize_strategy.ts
│       │       ├── get_risk_metrics.ts
│       │       ├── get_correlation_matrix.ts
│       │       └── walk_forward_test.ts
│       └── README.md
```

## Tools Exposed

### `run_backtest`

```typescript
{
  name: "run_backtest",
  description: "Run a backtest of a single strategy on a pair/interval over a date range. Returns trades, equity curve, and summary stats.",
  inputSchema: {
    type: "object",
    required: ["strategy", "pair", "interval", "startDate", "endDate"],
    properties: {
      strategy: { type: "string", enum: [
        "EMA_CROSSOVER", "MACD", "BOLLINGER", "RSI_MOMENTUM",
        "STOCH_RSI", "TRIPLE_EMA", "PARABOLIC_SAR", "ADX_DI",
        "CCI", "MFI", "DONCHIAN", "ICHIMOKU"
      ]},
      pair: { type: "string", enum: ["BTC-EUR", "ETH-EUR", "SOL-EUR"] },
      interval: { type: "integer", enum: [15, 60, 240, 1440] },
      startDate: { type: "string", format: "date-time" },
      endDate: { type: "string", format: "date-time" },
      paramOverrides: { type: "object", additionalProperties: true }
    }
  }
}
```

Handler:
```typescript
async function runBacktest(args: RunBacktestArgs) {
  const response = await httpClient.post("/api/backtest/run", args);
  return {
    content: [{
      type: "text",
      text: formatBacktestResult(response.data)
    }]
  };
}
```

### `compare_intervals`

Unique to Phase 11 — compares the same strategy across multiple intervals:

```typescript
{
  name: "compare_intervals",
  description: "Compare a strategy's performance across multiple intervals (e.g. 15m vs 1h vs 4h) on the same pair and date range.",
  inputSchema: {
    // similar to run_backtest but `intervals: number[]` instead of `interval: number`
  }
}
```

### `compare_strategies`

```typescript
{
  name: "compare_strategies",
  description: "Rank all 12 strategies on the same pair/interval/date range. Returns sorted by Calmar ratio by default.",
  inputSchema: {
    type: "object",
    required: ["pair", "interval", "startDate", "endDate"],
    properties: {
      pair: { type: "string" },
      interval: { type: "integer" },
      startDate: { type: "string" },
      endDate: { type: "string" },
      sortBy: { type: "string",
                enum: ["calmar", "sharpe", "totalPnl", "winRate", "expectancy"],
                default: "calmar" }
    }
  }
}
```

### `optimize_strategy`

Maps directly to `POST /api/backtest/optimize`.

### `walk_forward_test`

Maps to `POST /api/backtest/optimize-walkforward`.

### `get_risk_metrics`

Maps to `GET /api/analytics/risk?pair=...&interval=...&strategy=...`.

### `get_correlation_matrix`

Maps to `GET /api/analytics/correlation/*` endpoints. Returns the `insights` summary by default (more useful than raw matrix).

## MCP Configuration

Add to `/Users/stefanosgeorgiou/IdeaProjects/revolut-trading-bot-X/.claude/settings.local.json`:

```json
{
  "permissions": { ... },
  "mcpServers": {
    "postgres": { ... existing ... },
    "backtest": {
      "command": "node",
      "args": [
        "/Users/stefanosgeorgiou/IdeaProjects/revolut-trading-bot-X/mcp-servers/backtest/dist/index.js"
      ],
      "env": {
        "BACKTEST_API_BASE": "http://localhost:8080"
      }
    }
  }
}
```

The Spring Boot backend must be running locally on port 8080 for the MCP to work.

## Formatting Responses for Claude

Raw JSON is verbose. Format for readable output:

```typescript
function formatBacktestResult(result: BacktestResponse): string {
  return `
Backtest: ${result.request.strategy} on ${result.request.pair} (${result.request.interval}min)
Period: ${result.request.startDate} to ${result.request.endDate}

=== Stats ===
Total trades: ${result.stats.totalTrades}
Win rate: ${(result.stats.winRate * 100).toFixed(1)}%
Total PnL: €${result.stats.totalPnl.toFixed(2)}
Profit factor: ${result.stats.profitFactor.toFixed(2)}
Max drawdown: €${result.stats.maxDrawdown.toFixed(2)} (${(result.stats.maxDrawdownPct * 100).toFixed(2)}%)
Expectancy per trade: €${result.stats.expectancy.toFixed(2)}

=== Top 5 trades ===
${result.trades.slice(0, 5).map(t =>
  `${t.entry} → ${t.exit} | €${t.pnl.toFixed(2)} (${(t.pnlPct * 100).toFixed(2)}%) | ${t.exitReason}`
).join('\n')}
`.trim();
}
```

## Error Handling

- Spring Boot backend unreachable: return a clear error ("Is the bot running? `mvn spring-boot:run`")
- Invalid parameter: rely on the Spring validation, surface the validation error
- Timeout: default 30s; optimization endpoints may legitimately take minutes — add a tool parameter for `timeoutSeconds`

## Verification

1. Build: `cd mcp-servers/backtest && npm install && npm run build`
2. Test directly: `node dist/index.js` — should start MCP stdio server
3. Add to Claude Code settings, restart session
4. Ask Claude: "run a backtest of ICHIMOKU on BTC-EUR 1h for March 2026"
5. Claude should pick up the `run_backtest` tool automatically
6. Compare tool output against direct `curl` to the REST endpoint
7. Test `compare_strategies` — expect all 12 strategies in the response

## Why a Separate MCP Wrapper (Not Just REST)

Could Claude Code just use `curl` against the REST endpoints? Technically yes, via Bash. But:

1. **Discoverability** — MCP tools appear in Claude's tool list with schema. Claude naturally reaches for them.
2. **Structured inputs** — JSONSchema validation at the tool boundary prevents malformed requests.
3. **Formatted output** — Human-readable summaries instead of raw JSON walls of text.
4. **No shell escaping** — JSON strings with quotes don't break when passed as tool arguments vs Bash args.

## Follow-ups

- Add a `persist_optimization_result` tool that writes optimal params to a config file
- Add a `visualize_equity_curve` tool that outputs an ASCII chart of the equity curve
- Add a `what_if` tool for counterfactual analysis: "what if we added a stop-loss-tightening rule?"
