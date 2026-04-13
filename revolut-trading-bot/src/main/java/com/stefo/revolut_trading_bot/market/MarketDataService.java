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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
     * Fetches the latest candles for the given pair and interval from the Revolut API,
     * persists new/updated candles, rebuilds the BarSeries, and caches it.
     *
     * @param pair          trading pair e.g. "BTC-EUR"
     * @param intervalLabel interval label e.g. "15m", "1h"
     * @return the freshly built BarSeries for this (pair, interval)
     */
    public BarSeries fetchBarSeriesForPairAndInterval(String pair, String intervalLabel) {
        int intervalMinutes = TradingConfig.intervalMinutes(intervalLabel);
        Duration barDuration = Duration.ofMinutes(intervalMinutes);

        // Find the latest candle we already have so we only download new ones.
        // On the very first run (empty DB) sinceMs=0 → API returns full history.
        long sinceMs = candlestickRepository
                .findTopByPairAndIntervalOrderByTimestampDesc(pair, intervalLabel)
                .map(c -> c.getTimestamp().toInstant(ZoneOffset.UTC).toEpochMilli())
                .orElse(0L);

        log.info("Fetching candles for {} interval {} since={}", pair, intervalLabel, sinceMs);

        List<CandleResponse> candles = marketDataClient.getCandles(pair, intervalMinutes, sinceMs);
        log.info("[{}][{}] Received {} new candles from API", pair, intervalLabel, candles.size());

        persistCandles(candles, pair, intervalLabel);

        BarSeries series = buildBarSeries(pair, intervalLabel, barDuration);
        barSeriesMap.put(cacheKey(pair, intervalLabel), series);

        log.info("[{}][{}] BarSeries built with {} bars", pair, intervalLabel, series.getBarCount());
        return series;
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
        BarSeries cached = barSeriesMap.get(key);
        if (cached != null) {
            return cached;
        }
        log.debug("[{}][{}] No cached BarSeries — building from DB", pair, intervalLabel);
        Duration barDuration = Duration.ofMinutes(TradingConfig.intervalMinutes(intervalLabel));
        BarSeries series = buildBarSeriesFromDb(pair, intervalLabel, barDuration);
        barSeriesMap.put(key, series);
        return series;
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

    private void persistCandles(List<CandleResponse> candles, String pair, String intervalLabel) {
        int newCount = 0;
        for (CandleResponse candle : candles) {
            // API returns 'start' (candle open time) in Unix epoch ms
            LocalDateTime timestamp = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(candle.start()), ZoneOffset.UTC);

            Optional<Candlestick> existing = candlestickRepository
                    .findByPairAndIntervalAndTimestamp(pair, intervalLabel, timestamp);

            if (existing.isEmpty()) {
                Candlestick entity = Candlestick.builder()
                        .pair(pair)
                        .interval(intervalLabel)
                        .openPrice(candle.open())
                        .highPrice(candle.high())
                        .lowPrice(candle.low())
                        .closePrice(candle.close())
                        .volume(candle.volume())
                        .timestamp(timestamp)
                        .build();
                candlestickRepository.save(entity);
                newCount++;
            } else {
                // Update the latest (still-forming) candle
                Candlestick entity = existing.get();
                entity.setClosePrice(candle.close());
                entity.setHighPrice(candle.high());
                entity.setLowPrice(candle.low());
                entity.setVolume(candle.volume());
                candlestickRepository.save(entity);
            }
        }
        log.debug("[{}][{}] Candle sync: {} new, {} updated", pair, intervalLabel, newCount, candles.size() - newCount);
    }

    private BarSeries buildBarSeries(String pair, String intervalLabel, Duration barDuration) {
        List<Candlestick> candles = candlestickRepository
                .findByPairAndIntervalOrderByTimestampAsc(pair, intervalLabel);
        return buildBarSeriesFromCandles(pair + "_" + intervalLabel, candles, barDuration);
    }

    private BarSeries buildBarSeriesFromDb(String pair, String intervalLabel, Duration barDuration) {
        List<Candlestick> candles = candlestickRepository
                .findByPairAndIntervalOrderByTimestampAsc(pair, intervalLabel);
        BarSeries series = buildBarSeriesFromCandles(pair + "_" + intervalLabel, candles, barDuration);
        log.info("[{}][{}] BarSeries built from DB with {} bars", pair, intervalLabel, series.getBarCount());
        return series;
    }

    private BarSeries buildBarSeriesFromCandles(String seriesName, List<Candlestick> candles, Duration barDuration) {
        BaseBarSeries series = new BaseBarSeries(seriesName);
        for (Candlestick c : candles) {
            ZonedDateTime endTime = c.getTimestamp().atZone(ZoneOffset.UTC).plus(barDuration);
            Bar bar = BaseBar.builder()
                    .timePeriod(barDuration)
                    .endTime(endTime)
                    .openPrice(DecimalNum.valueOf(c.getOpenPrice()))
                    .highPrice(DecimalNum.valueOf(c.getHighPrice()))
                    .lowPrice(DecimalNum.valueOf(c.getLowPrice()))
                    .closePrice(DecimalNum.valueOf(c.getClosePrice()))
                    .volume(DecimalNum.valueOf(c.getVolume()))
                    .build();
            series.addBar(bar);
        }
        return series;
    }
}
