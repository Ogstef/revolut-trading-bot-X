package com.stefo.revolut_trading_bot.market;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.dto.CandleResponse;
import com.stefo.revolut_trading_bot.model.dto.TickerResponse;
import com.stefo.revolut_trading_bot.model.entity.Candlestick;
import com.stefo.revolut_trading_bot.repository.CandlestickRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.num.DecimalNum;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataService {

    private final MarketDataClient      marketDataClient;
    private final CandlestickRepository candlestickRepository;
    private final TradingConfig         tradingConfig;

    // One BarSeries per (pair, interval) combination — composite key "BTC-EUR::15m".
    // ConcurrentHashMap because the trading loop and potential REST reads run on different threads.
    private final Map<String, BarSeries> barSeriesMap = new ConcurrentHashMap<>();

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Fetches the latest candles from the Revolut API, persists them, and incrementally
     * updates the cached BarSeries for this (pair, interval).
     *
     * Hot-path optimization: on subsequent cycles we only append the new candles returned
     * by the API instead of rebuilding the full BarSeries from the DB. The first call per
     * (pair, interval) still warms the cache from the DB.
     *
     * The whole update runs inside {@link Map#compute(Object, java.util.function.BiFunction)}
     * so concurrent callers (trading loop vs REST test endpoints) can't double-fetch.
     */
    public BarSeries fetchBarSeriesForPairAndInterval(String pair, String intervalLabel) {
        int intervalMinutes = TradingConfig.intervalMinutes(intervalLabel);
        Duration barDuration = Duration.ofMinutes(intervalMinutes);
        String key = cacheKey(pair, intervalLabel);

        return barSeriesMap.compute(key, (k, cached) -> updateSeries(pair, intervalLabel, intervalMinutes, barDuration, cached));
    }

    /**
     * Backward-compatible wrapper — fetches candles for the primary interval.
     */
    public BarSeries fetchBarSeriesForPair(String pair) {
        return fetchBarSeriesForPairAndInterval(pair, tradingConfig.primaryInterval());
    }

    /**
     * Returns the cached BarSeries for the given pair and interval.
     * Falls back to reading from the DB if no in-memory series exists yet.
     */
    public BarSeries getBarSeriesForPairAndInterval(String pair, String intervalLabel) {
        String key = cacheKey(pair, intervalLabel);
        Duration barDuration = Duration.ofMinutes(TradingConfig.intervalMinutes(intervalLabel));
        return barSeriesMap.computeIfAbsent(key, k -> {
            log.debug("[{}][{}] No cached BarSeries — building from DB", pair, intervalLabel);
            return buildBarSeriesFromDb(pair, intervalLabel, barDuration);
        });
    }

    /**
     * Backward-compatible wrapper — returns cached BarSeries for the primary interval.
     */
    public BarSeries getBarSeriesForPair(String pair) {
        return getBarSeriesForPairAndInterval(pair, tradingConfig.primaryInterval());
    }

    /**
     * Returns the current market price for the given pair.
     * Uses the last close from any cached BarSeries if available;
     * falls back to a live ticker API call (cheaper than a full candle fetch).
     * Price is interval-agnostic — the market price is the same regardless of candle interval.
     */
    public BigDecimal getCurrentPriceForPair(String pair) {
        // Try the primary interval's cached series first
        String key = cacheKey(pair, tradingConfig.primaryInterval());
        BarSeries series = barSeriesMap.get(key);
        if (series != null && series.getBarCount() > 0) {
            return BigDecimal.valueOf(series.getLastBar().getClosePrice().doubleValue());
        }
        // Fallback: try any cached series for this pair
        for (String intervalLabel : tradingConfig.intervalLabels()) {
            BarSeries s = barSeriesMap.get(cacheKey(pair, intervalLabel));
            if (s != null && s.getBarCount() > 0) {
                return BigDecimal.valueOf(s.getLastBar().getClosePrice().doubleValue());
            }
        }
        return fetchCurrentPriceFromTicker(pair);
    }

    /**
     * Convenience method for callers that don't have explicit pair context.
     * Delegates to the primary (first) configured pair.
     */
    public BigDecimal getCurrentPrice() {
        return getCurrentPriceForPair(tradingConfig.primaryPair());
    }

    /**
     * Returns the cached BarSeries for the primary pair and primary interval.
     * Used by test endpoints and backward-compatible callers.
     */
    public BarSeries getBarSeries() {
        return getBarSeriesForPair(tradingConfig.primaryPair());
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private static String cacheKey(String pair, String intervalLabel) {
        return pair + "::" + intervalLabel;
    }

    private BigDecimal fetchCurrentPriceFromTicker(String pair) {
        List<TickerResponse> tickers = marketDataClient.getTickers(pair);
        if (tickers.isEmpty()) {
            log.warn("No ticker data available for {}", pair);
            return BigDecimal.ZERO;
        }
        // Use mid-price (average of best bid/ask) as a neutral current price reference
        BigDecimal mid = tickers.getFirst().mid();
        log.debug("[{}] Current price from ticker mid: {}", pair, mid);
        return mid;
    }

    /**
     * Fetches candles from the API, persists them, and returns an updated BarSeries.
     * - First call (cached == null): build the full series from the DB after persisting.
     * - Subsequent calls: append/replace only the bars returned by the API.
     */
    private BarSeries updateSeries(String pair, String intervalLabel, int intervalMinutes,
                                   Duration barDuration, BarSeries cached) {
        long sinceMs = resolveSinceMs(pair, intervalLabel, barDuration, cached);
        log.info("Fetching candles for {} interval {} since={}", pair, intervalLabel, sinceMs);

        List<CandleResponse> candles = marketDataClient.getCandles(pair, intervalMinutes, sinceMs);
        log.info("[{}][{}] Received {} candles from API", pair, intervalLabel, candles.size());

        persistCandles(candles, pair, intervalLabel);

        BarSeries series;
        if (cached == null) {
            series = buildBarSeriesFromDb(pair, intervalLabel, barDuration);
            log.info("[{}][{}] BarSeries built from DB with {} bars (cold start)",
                    pair, intervalLabel, series.getBarCount());
        } else {
            int before = cached.getBarCount();
            appendOrReplaceBars(cached, candles, barDuration);
            log.info("[{}][{}] BarSeries incrementally updated: {} → {} bars",
                    pair, intervalLabel, before, cached.getBarCount());
            series = cached;
        }
        return series;
    }

    /**
     * Picks the 'since' epoch ms for the next candle fetch:
     *   - cached series present: the last bar's start time (replaces the still-forming bar and picks up any new ones)
     *   - no cache but DB has candles: latest persisted candle timestamp
     *   - empty DB: 0 (full history)
     */
    private long resolveSinceMs(String pair, String intervalLabel, Duration barDuration, BarSeries cached) {
        if (cached != null && cached.getBarCount() > 0) {
            // endTime − barDuration = start time of the last bar
            return cached.getLastBar().getEndTime().minus(barDuration).toInstant().toEpochMilli();
        }
        return candlestickRepository.findTopByPairAndIntervalOrderByTimestampDesc(pair, intervalLabel)
                .map(c -> c.getTimestamp().toInstant(ZoneOffset.UTC).toEpochMilli())
                .orElse(0L);
    }

    /**
     * Bulk upsert: one SELECT to find existing rows, one batched INSERT/UPDATE via saveAll.
     * Replaces the previous find-then-save-per-candle loop (N+1 queries → 2 queries).
     */
    private void persistCandles(List<CandleResponse> candles, String pair, String intervalLabel) {
        if (candles.isEmpty()) {
            return;
        }

        List<LocalDateTime> timestamps = candles.stream()
                .map(c -> LocalDateTime.ofInstant(Instant.ofEpochMilli(c.start()), ZoneOffset.UTC))
                .toList();

        Map<LocalDateTime, Candlestick> existing = candlestickRepository
                .findByPairAndIntervalAndTimestampIn(pair, intervalLabel, timestamps)
                .stream()
                .collect(Collectors.toMap(Candlestick::getTimestamp, Function.identity()));

        List<Candlestick> toSave = new ArrayList<>(candles.size());
        int newCount = 0;
        for (CandleResponse candle : candles) {
            LocalDateTime timestamp = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(candle.start()), ZoneOffset.UTC);
            Candlestick entity = existing.get(timestamp);
            if (entity == null) {
                entity = Candlestick.builder()
                        .pair(pair)
                        .interval(intervalLabel)
                        .openPrice(candle.open())
                        .highPrice(candle.high())
                        .lowPrice(candle.low())
                        .closePrice(candle.close())
                        .volume(candle.volume())
                        .timestamp(timestamp)
                        .build();
                newCount++;
            } else {
                entity.setClosePrice(candle.close());
                entity.setHighPrice(candle.high());
                entity.setLowPrice(candle.low());
                entity.setVolume(candle.volume());
            }
            toSave.add(entity);
        }
        candlestickRepository.saveAll(toSave);
        log.debug("[{}][{}] Candle sync: {} new, {} updated", pair, intervalLabel,
                newCount, candles.size() - newCount);
    }

    /**
     * Appends each API candle to the cached series, or replaces the last bar when the
     * candle's end time matches (the still-forming bar). API candles older than the
     * current last bar are skipped (shouldn't happen — defensive).
     */
    private void appendOrReplaceBars(BarSeries series, List<CandleResponse> candles, Duration barDuration) {
        for (CandleResponse candle : candles) {
            Bar bar = toBar(candle, barDuration);

            if (series.getBarCount() == 0) {
                series.addBar(bar);
                continue;
            }
            ZonedDateTime lastEndTime = series.getLastBar().getEndTime();
            if (bar.getEndTime().isAfter(lastEndTime)) {
                series.addBar(bar);
            } else if (bar.getEndTime().isEqual(lastEndTime)) {
                series.addBar(bar, true); // replace still-forming bar
            }
            // else: older than last cached bar — skip
        }
    }

    private BarSeries buildBarSeriesFromDb(String pair, String intervalLabel, Duration barDuration) {
        List<Candlestick> candles = candlestickRepository
                .findByPairAndIntervalOrderByTimestampAsc(pair, intervalLabel);
        BaseBarSeries series = new BaseBarSeries(pair + "_" + intervalLabel);
        for (Candlestick c : candles) {
            series.addBar(toBar(c, barDuration));
        }
        return series;
    }

    private Bar toBar(Candlestick c, Duration barDuration) {
        ZonedDateTime endTime = c.getTimestamp().atZone(ZoneOffset.UTC).plus(barDuration);
        return BaseBar.builder()
                .timePeriod(barDuration)
                .endTime(endTime)
                .openPrice(DecimalNum.valueOf(c.getOpenPrice()))
                .highPrice(DecimalNum.valueOf(c.getHighPrice()))
                .lowPrice(DecimalNum.valueOf(c.getLowPrice()))
                .closePrice(DecimalNum.valueOf(c.getClosePrice()))
                .volume(DecimalNum.valueOf(c.getVolume()))
                .build();
    }

    private Bar toBar(CandleResponse candle, Duration barDuration) {
        ZonedDateTime endTime = Instant.ofEpochMilli(candle.start())
                .atZone(ZoneOffset.UTC).plus(barDuration);
        return BaseBar.builder()
                .timePeriod(barDuration)
                .endTime(endTime)
                .openPrice(DecimalNum.valueOf(candle.open()))
                .highPrice(DecimalNum.valueOf(candle.high()))
                .lowPrice(DecimalNum.valueOf(candle.low()))
                .closePrice(DecimalNum.valueOf(candle.close()))
                .volume(DecimalNum.valueOf(candle.volume()))
                .build();
    }
}
