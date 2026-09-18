# D1 — News Sentiment Service [P2]

**Type:** Runtime Spring service
**Effort:** 1 day
**Value:** Medium — noisy but prevents trading into major news events
**Dependencies:** A2 (MarketContextService)

## Purpose

Prevent the bot from opening positions right when major negative news breaks (hacks, regulatory actions, exchange collapses). Technical indicators are blind to "Binance CEO arrested" or "SEC sues ETH" events — news sentiment is the fastest signal for these.

## API Options

### CryptoPanic (Recommended)

- **URL:** `https://cryptopanic.com/api/v1/posts/`
- **Free tier:** 500 requests/day, per-coin filtering, built-in `kind` classification (positive/negative/important)
- **Parameters:** `?auth_token={key}&currencies={BTC,ETH,SOL}&kind=news&filter=important`
- **Returns:** Array of posts with title, URL, source, bullish/bearish votes, published_at

Example response:
```json
{
  "results": [
    {
      "id": "123",
      "title": "ETH upgrade successfully deployed on mainnet",
      "currencies": [{"code": "ETH"}],
      "votes": { "negative": 2, "positive": 18, "important": 5 },
      "kind": "news",
      "published_at": "2026-04-14T10:30:00Z"
    }
  ]
}
```

### Alternatives

- **LunarCrush** — aggregates Twitter + Reddit + news; 50 calls/day free
- **Santiment** — social volume + sentiment; 50 calls/day free
- **The Tie** — professional tier only

## New Files

| File | Purpose |
|------|---------|
| `sentiment/NewsSentimentService.java` | Fetches + caches CryptoPanic data |
| `sentiment/PairSentiment.java` | Record: `{pair, bullish, bearish, neutral, netScore}` |
| `model/dto/CryptoPanicResponse.java` | API response parsing |

## Service Implementation

```java
@Slf4j
@Service
public class NewsSentimentService {

    private static final String API_URL_TEMPLATE =
        "https://cryptopanic.com/api/v1/posts/?auth_token=%s&currencies=%s&filter=important";
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);

    private final String apiKey;  // from env var CRYPTOPANIC_API_KEY
    private final RestClient restClient = RestClient.create();

    private final Map<String, CachedSentiment> cache = new ConcurrentHashMap<>();

    public PairSentiment getForPair(String pair) {
        String baseCurrency = pair.split("-")[0];  // "BTC-EUR" -> "BTC"
        CachedSentiment cached = cache.get(baseCurrency);

        if (cached != null && Instant.now().isBefore(cached.cachedAt().plus(CACHE_TTL))) {
            return cached.sentiment();
        }

        return refresh(baseCurrency);
    }

    private synchronized PairSentiment refresh(String currency) {
        // ... HTTP call, parse, compute net score, update cache
    }

    private BigDecimal computeNetScore(List<Post> posts) {
        // Weight recent posts higher than older ones
        // Net score = (bullish_weight - bearish_weight) / total_weight, scaled to -100..+100
    }
}
```

## Net Score Computation

For each post within the last 24 hours:

- `bullishWeight += votes.positive * timeDecay(post.publishedAt)`
- `bearishWeight += votes.negative * timeDecay(post.publishedAt)`
- `importanceBoost = votes.important * 2`

Where `timeDecay(t)` = `exp(-hoursAgo / 12)` — full weight for posts in the last hour, half weight at 12 hours old.

Final `netScore = ((bullish - bearish) / (bullish + bearish + 1)) * 100`, clamped to [-100, +100].

## Integration into MarketContext

Extend `MarketContext` record:

```java
public record MarketContext(
    int fearGreedValue,
    BigDecimal orderBookRatio,
    HtfTrend higherTimeframeTrend,
    BigDecimal btcDominance,
    BigDecimal marketCapChange24h,
    BigDecimal newsSentiment,       // NEW: -100 to +100
    BigDecimal multiplier
) {}
```

## Multiplier Logic

| News net score | Interpretation | BUY multiplier | SELL multiplier |
|----------------|----------------|----------------|-----------------|
| < -50 | Very bearish news | 0.70 | 1.20 |
| -50 to -20 | Bearish news | 0.90 | 1.10 |
| -20 to +20 | Neutral | 1.00 | 1.00 |
| +20 to +50 | Bullish news | 1.10 | 0.90 |
| > +50 | Very bullish news | 1.20 | 0.80 |

## Important Caveats

### Rate limit management

Free tier = 500 calls/day. With 3 pairs refreshing every 15 min:
```
3 pairs × (60 min / 15 min TTL) × 24 hours = 288 calls/day
```

Leaves headroom for retry/failures. If scaled to more pairs, reduce TTL or upgrade plan.

### News is noisy

News sentiment is a **dampening** signal, not a primary signal. Never let a positive news score alone trigger a BUY — it should only modulate signals from technical strategies.

### Regulatory differences

"SEC approves spot Bitcoin ETF" is bullish. "SEC sues Coinbase" is bearish for centralized exchanges specifically. CryptoPanic's built-in classification handles most of this but is imperfect.

### Timezone-sensitive

News during US trading hours dominates — weekend news often over-weighted. Consider clamping multiplier range during low-volume hours.

## Dashboard Integration (Optional)

Expose on dashboard so users can see what's driving multiplier adjustments:

```
GET /api/market/news-sentiment?pair=BTC-EUR
Returns: {
  "pair": "BTC-EUR",
  "netScore": 45.3,
  "recentPosts": [
    {"title": "...", "source": "CoinDesk", "published": "...", "votes": {...}}
  ]
}
```

Useful for debugging "why did my BUY signal get dampened?"

## Configuration

Add to `application.yml`:

```yaml
sentiment:
  cryptopanic:
    enabled: true
    api-key: ${CRYPTOPANIC_API_KEY:}  # optional
    cache-ttl-minutes: 15
```

If `enabled: false` or API key missing: service returns neutral sentiment (net score = 0) — bot still works, just skips this context modifier.

## Verification

1. Hit CryptoPanic API directly — confirm response shape
2. Start service with valid API key — verify logs show sentiment computation
3. Mock a bearish news scenario (inject negative posts) — verify BUY multiplier drops
4. Rate limit test: make 5 rapid calls — verify only one HTTP request made (cache works)
5. Disable service (`enabled: false`) — verify bot still runs, returns neutral sentiment
6. API failure test: block outbound to cryptopanic.com — verify graceful fallback

## Follow-ups

- Integrate social sentiment (LunarCrush) as a separate but complementary source
- Build a combined sentiment signal: news + social + F&G
- Historical sentiment backtesting (requires paid CryptoPanic plan for historical access)
