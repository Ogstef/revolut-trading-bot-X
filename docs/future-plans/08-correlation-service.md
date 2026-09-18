# C2 — CorrelationService [P1]

**Type:** Runtime Spring service
**Effort:** 1 day
**Value:** Medium-High — reveals hidden portfolio-level risk
**Dependencies:** A3 (Backtest engine)

## Purpose

With 72 independent virtual portfolios, it's easy to assume they provide diversification. But if all 12 strategies buy BTC-EUR at roughly the same time, **the portfolio is effectively one big BTC bet** — not 12 diversified bets.

Correlation analysis reveals:

- Which strategies move together (redundant — keep one, drop the rest)
- Which pairs are hedged vs. concentrated
- Which intervals provide genuine time-diversification

## New Files

| File | Purpose |
|------|---------|
| `backtest/CorrelationService.java` | Computes correlation matrices |
| `backtest/CorrelationMatrix.java` | Record: labels + 2D correlation array |
| `backtest/CorrelationInsight.java` | Record: human-readable finding |

## Correlation Types

### 1. Equity-curve correlation

For every pair of portfolios, compute Pearson correlation of their equity curves over the same time period. High correlation (>0.8) = redundant.

```
strategy_A.equity[t] vs strategy_B.equity[t] for t in [start, end]
```

### 2. Signal correlation

For every pair of portfolios, compute correlation of BUY/SELL timing. Useful for detecting strategies that fire signals at the same bars.

### 3. Return correlation

Daily PnL correlation — more stable than equity curve correlation for volatile strategies.

## Use Cases

### A. Pruning redundant portfolios

"Which of the 72 portfolios are so correlated they don't add diversification?"

```java
List<Pair> redundantPairs = correlationService.getEquityCurveMatrix()
    .upperTriangle()
    .filter(entry -> entry.correlation() > 0.85)
    .toList();
```

Example finding: "`(BTC-EUR, EMA_CROSSOVER, 15m)` and `(BTC-EUR, TRIPLE_EMA, 15m)` are 0.94 correlated — effectively the same bet."

### B. Pair-level concentration

Group correlations by pair — if all portfolios within `BTC-EUR` are highly correlated with each other, the pair is over-concentrated.

### C. Interval diversification

Does running both 15m and 1h of the same strategy provide time diversification? Check correlation between `(BTC-EUR, EMA_CROSSOVER, 15m)` and `(BTC-EUR, EMA_CROSSOVER, 1h)`. If > 0.9, intervals are redundant; if 0.3-0.6, genuine diversification.

## REST Endpoints

```
GET /api/analytics/correlation/equity
Returns: 72×72 matrix of equity curve correlations

GET /api/analytics/correlation/signals
Returns: 72×72 matrix of signal timing correlations

GET /api/analytics/correlation/by-pair?pair=BTC-EUR
Returns: 24×24 matrix (12 strategies × 2 intervals = 24 portfolios on BTC-EUR)

GET /api/analytics/correlation/insights
Returns: [
  {type: "REDUNDANT_PAIR", portfolios: ["A", "B"], correlation: 0.94,
   recommendation: "Drop one; they are redundant."},
  {type: "OVER_CONCENTRATION", pair: "BTC-EUR", avgIntraCorrelation: 0.71,
   recommendation: "BTC portfolios are highly clustered; consider reducing balance allocation."},
  {type: "INTERVAL_DIVERSIFICATION", strategy: "ICHIMOKU", pair: "ETH-EUR",
   correlation: 0.38,
   recommendation: "15m and 1h provide genuine diversification; keep both."}
]
```

The `/insights` endpoint is the most useful — it summarizes the matrices as actionable findings.

## Computation Details

### Pearson correlation (for continuous values)

```java
public BigDecimal pearson(List<BigDecimal> x, List<BigDecimal> y) {
    if (x.size() != y.size() || x.isEmpty()) {
        throw new IllegalArgumentException("Mismatched or empty series");
    }

    BigDecimal meanX = mean(x);
    BigDecimal meanY = mean(y);

    BigDecimal cov = BigDecimal.ZERO;
    BigDecimal varX = BigDecimal.ZERO;
    BigDecimal varY = BigDecimal.ZERO;

    for (int i = 0; i < x.size(); i++) {
        BigDecimal dx = x.get(i).subtract(meanX);
        BigDecimal dy = y.get(i).subtract(meanY);
        cov = cov.add(dx.multiply(dy));
        varX = varX.add(dx.multiply(dx));
        varY = varY.add(dy.multiply(dy));
    }

    BigDecimal denominator = sqrt(varX.multiply(varY));
    if (denominator.signum() == 0) return BigDecimal.ZERO;
    return cov.divide(denominator, 4, RoundingMode.HALF_UP);
}
```

### Matching time axes

Different portfolios trade at different times → can't directly correlate their trade lists. Solution: align to a shared daily grid and compute daily PnL per portfolio.

```java
Map<LocalDate, BigDecimal> dailyPnl(String pair, StrategyType strategy, String interval) {
    return tradeRepository
        .findByPairAndIntervalAndStrategyNameOrderByExecutedAtDesc(pair, interval, strategy)
        .stream()
        .filter(t -> t.getPnl() != null)
        .collect(groupingBy(
            t -> t.getClosedAt().toLocalDate(),
            reducing(BigDecimal.ZERO, Trade::getPnl, BigDecimal::add)
        ));
}
```

Then fill missing dates with 0 to get a complete daily series over the analysis window.

## Implementation Sketch

```java
@Service
@RequiredArgsConstructor
public class CorrelationService {

    private final TradeRepository tradeRepository;

    public CorrelationMatrix computeEquityMatrix(LocalDate start, LocalDate end) {
        List<PortfolioKey> portfolios = enumerateAllPortfolios();

        // Precompute daily PnL for each portfolio
        Map<PortfolioKey, List<BigDecimal>> series = portfolios.stream()
            .collect(toMap(k -> k, k -> dailySeries(k, start, end)));

        int n = portfolios.size();
        BigDecimal[][] matrix = new BigDecimal[n][n];

        for (int i = 0; i < n; i++) {
            for (int j = i; j < n; j++) {
                BigDecimal corr = (i == j)
                    ? BigDecimal.ONE
                    : pearson(series.get(portfolios.get(i)),
                              series.get(portfolios.get(j)));
                matrix[i][j] = corr;
                matrix[j][i] = corr;
            }
        }

        return new CorrelationMatrix(labels(portfolios), matrix);
    }

    public List<CorrelationInsight> generateInsights(CorrelationMatrix matrix) {
        // Scan matrix for patterns, return human-readable findings
    }
}
```

## Caching

Correlation matrices are expensive (O(N²) with N=72 portfolios) but stable. Cache with 1-hour TTL:

```java
@Cacheable(value = "correlations", key = "#start + ':' + #end")
public CorrelationMatrix computeEquityMatrix(LocalDate start, LocalDate end) { ... }
```

## Verification

1. Synthetic test: two portfolios with identical daily PnL → correlation = 1.00
2. Synthetic test: two portfolios with anti-correlated PnL → correlation = -1.00
3. Synthetic test: uncorrelated random series → correlation near 0
4. Real data: check that `(BTC-EUR, EMA_CROSSOVER, 15m)` and `(BTC-EUR, TRIPLE_EMA, 15m)` are positively correlated (both trend-following)
5. Real data: check insights endpoint returns readable recommendations
6. Verify symmetry: `matrix[i][j] == matrix[j][i]`
7. Verify diagonal: `matrix[i][i] == 1.0`

## Visualization

Useful to pair with a frontend heatmap — not in scope for this document, but the matrix format is ready for direct Recharts/D3 rendering.

## Follow-ups

- Time-varying correlation (correlation can change in market regimes)
- Conditional correlation (correlation during drawdowns vs. during bull runs)
- Principal Component Analysis — reduce 72 portfolios to their few underlying factors
