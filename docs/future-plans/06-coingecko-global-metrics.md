# B3 — CoinGecko Global Metrics Service [P1]

**Type:** Runtime Spring service
**Effort:** 1 day
**Value:** Medium-High — macro context prevents trading against the market
**Dependencies:** A2 (MarketContextService)

## Purpose

Bring macro-level crypto market awareness into signal generation. The Revolut X API gives per-pair OHLCV but no sense of whether the broader market is expanding or contracting.

Key macro data that matters for this bot:

- **BTC dominance %** — when rising, capital is rotating from alts (ETH, SOL) to BTC
- **Total crypto market cap** — absolute contraction is bearish for everything
- **24h market cap change** — velocity matters

## API

- **Endpoint:** `GET https://api.coingecko.com/api/v3/global`
- **Authentication:** None required for the public endpoint
- **Rate limit:** Free tier allows 10–30 calls/minute — generous for 5-minute cache
- **No API key needed** for this endpoint on the free plan

Response shape (abbreviated):
```json
{
  "data": {
    "active_cryptocurrencies": 12345,
    "markets": 1050,
    "total_market_cap": { "usd": 2.9e12, "eur": 2.7e12 },
    "market_cap_percentage": { "btc": 58.2, "eth": 13.1, "sol": 3.4 },
    "market_cap_change_percentage_24h_usd": -2.1,
    "updated_at": 1712345678
  }
}
```

## New Files

| File | Purpose |
|------|---------|
| `market/CoinGeckoService.java` | Fetches global metrics, caches |
| `model/dto/CryptoGlobalMetrics.java` | Record with fields we care about |

## Pattern — Mirror FearGreedService

Follow the exact pattern from `service/FearGreedService.java`:

```java
@Slf4j
@Service
public class CoinGeckoService {

    private static final String API_URL = "https://api.coingecko.com/api/v3/global";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final RestClient restClient = RestClient.create();
    private volatile CryptoGlobalMetrics cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public CryptoGlobalMetrics get() {
        if (cached == null || Instant.now().isAfter(cachedAt.plus(CACHE_TTL))) {
            refresh();
        }
        return cached;
    }

    private synchronized void refresh() {
        if (cached != null && Instant.now().isBefore(cachedAt.plus(CACHE_TTL))) return;
        try {
            CoinGeckoGlobalResponse response = restClient.get()
                .uri(API_URL)
                .retrieve()
                .body(CoinGeckoGlobalResponse.class);

            if (response != null && response.data() != null) {
                cached = new CryptoGlobalMetrics(
                    response.data().marketCapPercentage().get("btc"),
                    response.data().marketCapPercentage().get("eth"),
                    response.data().marketCapChangePercentage24hUsd(),
                    response.data().totalMarketCap().get("eur")
                );
                cachedAt = Instant.now();
                log.info("CoinGecko: BTC dominance {}%, 24h mcap change {}%",
                    cached.btcDominance(), cached.marketCapChange24h());
            }
        } catch (Exception e) {
            log.warn("Failed to fetch CoinGecko global metrics: {}", e.getMessage());
        }
    }

    // private record classes for API parsing ...
}
```

## Integration into MarketContextService

Extend `MarketContext` record:

```java
public record MarketContext(
    int fearGreedValue,
    BigDecimal orderBookRatio,
    HtfTrend higherTimeframeTrend,
    BigDecimal btcDominance,           // NEW
    BigDecimal marketCapChange24h,     // NEW
    BigDecimal multiplier
) {}
```

## Multiplier Logic

### Pair-specific logic for BTC dominance

- `pair = BTC-EUR`: BTC dominance has mixed impact (BTC rising at others' expense is actually bullish for BTC). **Skip this modifier for BTC-EUR**.
- `pair = ETH-EUR` or `SOL-EUR`:
  - BTC dominance > 60% AND rising week-over-week: cut multiplier by 15% (capital flowing to BTC, away from alts)
  - BTC dominance < 50%: boost multiplier by 5% (alt-friendly regime)

### Total market cap change (applies to all pairs)

- 24h change < -5%: cut all BUY multipliers by 15% (strong defensive posture)
- 24h change > +5%: boost all BUY multipliers by 5% (confirmed expansion)

## Exposing on Dashboard

Add an endpoint for the UI to display:

```
GET /api/market/global-metrics
Returns: CryptoGlobalMetrics
```

Frontend can show BTC dominance and 24h change alongside Fear & Greed for a full macro picture.

## Caching Strategy

- 5-minute TTL balances rate limit and data freshness
- Global metrics change slowly — 5 minutes is fine
- In-memory only (consistent with `FearGreedService`)
- If API fails, return last cached value — don't drag down the trading loop

## Graceful Degradation

If CoinGecko is unreachable:

```java
CryptoGlobalMetrics metrics = coinGeckoService.get();
if (metrics == null) {
    // Skip macro modifier — default multiplier unchanged
    log.debug("CoinGecko data unavailable, skipping macro context");
    return baseMultiplier;
}
```

## Verification

1. Hit `https://api.coingecko.com/api/v3/global` directly — verify response format
2. Start the service, wait for first fetch — inspect logs for `CoinGecko: BTC dominance X%`
3. Trigger `MarketContextService` manually with mock ETH-EUR signal and high BTC dominance — verify 15% multiplier cut
4. Simulate API failure (block outbound network to coingecko.com) — verify graceful fallback, no exceptions bubble up
5. Verify cache: call `get()` 100 times in 1 second — only one actual HTTP request made
6. Check `/api/market/global-metrics` returns expected payload

## Known Limitations

- CoinGecko free tier doesn't include historical global metrics — can't backtest with "what was BTC dominance a year ago"
- Fiat conversion (EUR) may have small lag — fine for macro signals

## Follow-ups

- Consider extending with historical dominance chart (paid CoinGecko API or Blockchain.com)
- Add CoinGecko MCP server for development-time queries (optional, [P2])
