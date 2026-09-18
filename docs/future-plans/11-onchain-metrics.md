# D2 — On-Chain Metrics Service [P2]

**Type:** Runtime Spring service
**Effort:** 2 days
**Value:** Medium — strong signal for BTC/ETH, limited for SOL
**Dependencies:** A2 (MarketContextService)

## Purpose

On-chain data reveals what **price** can't: where coins are moving, who's holding, and whether institutional players are accumulating or distributing.

The single most actionable metric: **exchange net flow**.

- **Net outflows** (coins leaving exchanges → cold storage): bullish (hodling signal)
- **Net inflows** (coins moving to exchanges): bearish (selling pressure)

## API Options

### CryptoQuant

- **Free tier:** Community account has limited endpoints; exchange flow requires paid tier
- **Strength:** Highest quality exchange flow data

### IntoTheBlock

- **Free tier:** Basic metrics (holder distribution, large transactions)
- **Strength:** Per-pair metrics

### Blockchain.com (BTC only)

- **Free tier:** Completely free, no API key
- **Strength:** Free, reliable; provides hash rate, mempool size, transaction count
- **Weakness:** BTC only — no ETH, no SOL

### Glassnode

- **Free tier:** Limited to basic metrics
- **Strength:** Gold standard for on-chain
- **Weakness:** $29/month for meaningful metrics

### Recommendation

**Start with Blockchain.com + IntoTheBlock free tier.** BTC coverage is solid; ETH/SOL gets limited metrics. Upgrade if value demonstrated.

## New Files

| File | Purpose |
|------|---------|
| `market/OnChainMetricsService.java` | Fetches + caches on-chain data |
| `market/OnChainContext.java` | Record: per-pair on-chain signals |
| `model/dto/BlockchainInfoResponse.java` | API response parsing |

## Key Metrics Per Coin

### BTC

| Metric | Source | Signal |
|--------|--------|--------|
| Hash rate | Blockchain.com | Rising = bullish (miner confidence) |
| Mempool size | Blockchain.com | Spikes = network congestion (neutral) |
| Exchange balance | IntoTheBlock | Declining = bullish |
| Large transactions (>$100K) | IntoTheBlock | Spikes = whale activity |

### ETH

| Metric | Source | Signal |
|--------|--------|--------|
| Gas price | Etherscan | High = network demand (bullish activity) |
| ETH on exchanges | IntoTheBlock | Declining = bullish |
| Staking ratio | Beaconchain | Rising = supply lock-up (bullish) |

### SOL

- Limited on-chain providers
- Solana FM API provides basic metrics but no sentiment-ready endpoints
- **May skip SOL on-chain initially** — use only price/technical signals for SOL

## Service Implementation

```java
@Slf4j
@Service
public class OnChainMetricsService {

    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final RestClient restClient = RestClient.create();
    private final Map<String, CachedMetrics> cache = new ConcurrentHashMap<>();

    public OnChainContext getForPair(String pair) {
        String baseCurrency = pair.split("-")[0];

        CachedMetrics cached = cache.get(baseCurrency);
        if (cached != null && Instant.now().isBefore(cached.cachedAt().plus(CACHE_TTL))) {
            return cached.context();
        }

        return switch (baseCurrency) {
            case "BTC" -> fetchBtcMetrics();
            case "ETH" -> fetchEthMetrics();
            case "SOL" -> OnChainContext.neutral();  // skip
            default   -> OnChainContext.neutral();
        };
    }

    private OnChainContext fetchBtcMetrics() {
        // Blockchain.com: GET https://api.blockchain.info/stats
        // IntoTheBlock (if configured): exchange balance endpoint
        // Compute: netFlow classification, whale activity
    }

    private OnChainContext fetchEthMetrics() {
        // Similar pattern, ETH-specific endpoints
    }
}
```

## `OnChainContext` Record

```java
public record OnChainContext(
    String baseCurrency,
    NetFlow exchangeNetFlow,        // enum: STRONG_OUTFLOW, OUTFLOW, NEUTRAL, INFLOW, STRONG_INFLOW
    WhaleActivity whaleActivity,    // enum: LOW, MEDIUM, HIGH
    BigDecimal hashRateTrend,       // (current - 7d_avg) / 7d_avg, BTC only
    BigDecimal stakingRatioTrend,   // ETH only
    BigDecimal multiplier
) {
    public static OnChainContext neutral() {
        return new OnChainContext(null,
            NetFlow.NEUTRAL, WhaleActivity.LOW, null, null, BigDecimal.ONE);
    }
}
```

## Multiplier Logic

| Exchange net flow | BUY multiplier | SELL multiplier |
|-------------------|----------------|-----------------|
| STRONG_OUTFLOW (>$500M) | 1.20 | 0.85 |
| OUTFLOW (>$100M) | 1.10 | 0.95 |
| NEUTRAL | 1.00 | 1.00 |
| INFLOW | 0.95 | 1.10 |
| STRONG_INFLOW | 0.85 | 1.20 |

| Whale activity | Multiplier (either direction) |
|----------------|-------------------------------|
| LOW | No change |
| MEDIUM | Reduce by 5% (uncertainty) |
| HIGH | Reduce by 10% (big moves coming, don't fight them) |

## Integration into MarketContext

Extend `MarketContext` record (final form):

```java
public record MarketContext(
    int fearGreedValue,
    BigDecimal orderBookRatio,
    HtfTrend higherTimeframeTrend,
    BigDecimal btcDominance,
    BigDecimal marketCapChange24h,
    BigDecimal newsSentiment,
    OnChainContext onChainContext,    // NEW
    BigDecimal multiplier
) {}
```

## Why This Is P2

On-chain data is the most delayed of all signals:

- Price reacts in seconds
- Fear & Greed reacts in hours
- News sentiment reacts in minutes-to-hours
- On-chain metrics typically update every 10 minutes to 1 hour

Still valuable for **confirming** longer-term directional bias, but too slow to trigger entry/exit alone. Best used as a **regime filter** — "are we in an accumulation phase or distribution phase?"

## Configuration

Add to `application.yml`:

```yaml
onchain:
  blockchain-info:
    enabled: true
    cache-ttl-minutes: 60
  intotheblock:
    enabled: false  # until API key obtained
    api-key: ${INTOTHEBLOCK_API_KEY:}
```

Default: Blockchain.com for BTC only, all other pairs return neutral. No API key required for initial rollout.

## Rate Limit Considerations

- Blockchain.com: no documented rate limit but be polite (1 request/minute is safe)
- IntoTheBlock free: 500 calls/day (generous)
- 1-hour cache is aggressive enough to never hit limits

## Verification

1. Hit Blockchain.com `/stats` endpoint directly — confirm response shape
2. Start service, wait for first BTC fetch — verify logs show hash rate/mempool values
3. Trigger `MarketContextService` on BTC-EUR — verify on-chain multiplier applied
4. Trigger on SOL-EUR — verify returns `OnChainContext.neutral()` (no SOL data)
5. API failure: block outbound to blockchain.info — verify graceful fallback
6. Cache: call `getForPair("BTC-EUR")` 100x — only 1 HTTP request should be made

## Historical Backtesting Limitation

**Cannot backtest on-chain integration** without historical on-chain data storage. Options:

- **Accept limitation:** on-chain service is forward-only
- **Persist snapshots:** add an `on_chain_snapshots` table, accumulate over time, backtest future improvements
- **Paid plan:** Glassnode / CryptoQuant paid tiers offer historical exports

Initial recommendation: accept the limitation; revisit if on-chain proves valuable in live paper trading.

## Follow-ups

- Add exchange-specific flow tracking (Binance inflows are different from Coinbase inflows)
- Miner sell pressure metrics for BTC (miner-to-exchange flow specifically)
- Stablecoin supply changes (USDT/USDC mints are leading indicators of crypto purchases)
- DEX vs CEX volume ratio (DEX rising = DeFi activity heating up)
