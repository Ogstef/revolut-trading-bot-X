# B2 — RiskAnalyticsService [P1]

**Type:** Runtime Spring service
**Effort:** 1 day
**Value:** High — quantifies risk per portfolio, required for LIVE mode confidence
**Dependencies:** A3 (Backtest engine)

## Purpose

Compute institutional-grade risk metrics for every `(pair, interval, strategy)` portfolio — both for historical trades (from `trading.trades`) and for backtest results.

These metrics answer questions like:

- "Which portfolio has the best risk-adjusted return?" → Sharpe / Sortino ratio
- "What's the worst case I should expect on 95% of days?" → VaR 95%
- "How much underwater can this strategy go?" → Max drawdown
- "Is the return worth the drawdown risk?" → Calmar ratio

## New Files

| File | Purpose |
|------|---------|
| `backtest/RiskAnalyticsService.java` | Computes all risk metrics |
| `backtest/RiskMetrics.java` | Record containing all computed metrics |

## Metrics Implemented

### Sharpe Ratio

Risk-adjusted return relative to a risk-free rate:

```
Sharpe = (mean_daily_return - risk_free_rate) / stddev_daily_returns
```

Annualized by multiplying by `sqrt(tradingDaysPerYear)` (365 for crypto, not 252).

Typical values:
- < 0.5 → poor
- 0.5 – 1.0 → acceptable
- 1.0 – 2.0 → good
- > 2.0 → excellent

### Sortino Ratio

Like Sharpe but only penalizes **downside** volatility — upside volatility is a feature, not a risk:

```
Sortino = (mean_daily_return - target_return) / stddev_of_negative_returns
```

More relevant than Sharpe for asymmetric-return strategies (most crypto strategies).

### Max Drawdown

Largest peak-to-trough decline in the equity curve:

```java
BigDecimal peak = BigDecimal.ZERO;
BigDecimal maxDrawdown = BigDecimal.ZERO;
for (EquityCurvePoint p : equityCurve) {
    if (p.equity().compareTo(peak) > 0) peak = p.equity();
    BigDecimal drawdown = peak.subtract(p.equity());
    if (drawdown.compareTo(maxDrawdown) > 0) maxDrawdown = drawdown;
}
```

Also reported as percentage: `maxDrawdown / peak`.

### Max Drawdown Duration

Longest time spent below a previous peak. Measures how long a strategy stays "underwater".

```java
Instant peakTime = equityCurve.get(0).timestamp();
Duration maxUnderwater = Duration.ZERO;
BigDecimal peakEquity = equityCurve.get(0).equity();
for (EquityCurvePoint p : equityCurve) {
    if (p.equity().compareTo(peakEquity) >= 0) {
        peakEquity = p.equity();
        peakTime = p.timestamp();
    } else {
        Duration underwater = Duration.between(peakTime, p.timestamp());
        if (underwater.compareTo(maxUnderwater) > 0) maxUnderwater = underwater;
    }
}
```

### Value at Risk (VaR 95%)

5th percentile of daily returns — "worst-case loss on 95% of days":

```java
List<BigDecimal> sortedReturns = dailyReturns.stream().sorted().toList();
int index = (int) Math.floor(sortedReturns.size() * 0.05);
BigDecimal var95 = sortedReturns.get(index);
```

Also compute `var99` (1st percentile) for tail-risk awareness.

### Calmar Ratio

Annualized return divided by max drawdown:

```
Calmar = annualized_return / max_drawdown_pct
```

Values > 3.0 are excellent — the strategy makes 3x its worst drawdown in a year.

### Profit Factor

Gross profit / gross loss:

```
profit_factor = sum(winning_trade_pnls) / abs(sum(losing_trade_pnls))
```

Values:
- < 1.0 → unprofitable
- 1.0 – 1.5 → marginal
- 1.5 – 2.0 → good
- > 2.0 → excellent

### Expectancy Per Trade

Already computed by `TradingStats` today — include in the output for convenience:

```
expectancy = (win_rate * avg_win) - ((1 - win_rate) * avg_loss)
```

## `RiskMetrics` Record

```java
public record RiskMetrics(
    String pair,
    String interval,
    StrategyType strategy,
    Instant periodStart,
    Instant periodEnd,
    int totalTrades,
    BigDecimal winRate,
    BigDecimal totalPnl,
    BigDecimal sharpeRatio,
    BigDecimal sortinoRatio,
    BigDecimal maxDrawdown,
    BigDecimal maxDrawdownPct,
    Duration maxDrawdownDuration,
    BigDecimal var95,
    BigDecimal var99,
    BigDecimal calmarRatio,
    BigDecimal profitFactor,
    BigDecimal expectancy
) {}
```

## API Endpoints

Extend `BacktestController`:

```
GET /api/analytics/risk?pair=BTC-EUR&interval=1h&strategy=ICHIMOKU
Returns: RiskMetrics for the historical trades of this portfolio

POST /api/analytics/risk-from-backtest
Body: BacktestResult
Returns: RiskMetrics computed from the backtest equity curve
```

## Implementation Notes

- Daily returns: group trades by `closed_at::date`, sum PnL per day. If a day has no trades, `dailyReturn = 0`.
- Risk-free rate: use configurable default (0.04 annual / 365 = daily rate ~0.00011)
- Use `BigDecimal` throughout — no `double` for money or ratios
- Cache results by `(pair, interval, strategy, periodEnd)` with 5-minute TTL (recomputation is expensive)

## Ranking Endpoint

Add a convenience endpoint to rank all 72 portfolios:

```
GET /api/analytics/rankings?metric=sharpe
Returns: [
  {pair: "BTC-EUR", strategy: "ICHIMOKU", interval: "1h", sharpe: 2.1, ...},
  ...
]
```

Great for answering "which portfolios should we keep, which should we drop?" in a single call.

## Verification

1. Create a synthetic `BacktestResult` with hand-calculated metrics
2. Pass through `RiskAnalyticsService` — verify computed metrics match
3. Test edge cases:
   - Zero trades → all ratios = 0 (no division by zero)
   - All winning trades → profit factor = ∞ (return sentinel, don't crash)
   - All losing trades → Sharpe < 0, profit factor < 1
   - One trade → stddev undefined → Sharpe = 0 (insufficient data)
4. Cross-check against Excel calculations on a known dataset
5. Compare rankings with `/api/strategies/{name}/stats` — should agree on win rate and total PnL

## Follow-ups

- [`08-correlation-service.md`](./08-correlation-service.md) — portfolio correlation adds another risk dimension
- [`09-custom-backtest-mcp.md`](./09-custom-backtest-mcp.md) — expose `get_risk_metrics` tool to Claude Code
