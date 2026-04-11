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

    // 15-minute candles — interval must be an integer (minutes), not a string like "15m"
    private static final int      CANDLE_INTERVAL_MINUTES = 15;
    private static final String   CANDLE_INTERVAL_LABEL   = "15m";
    private static final Duration BAR_DURATION            = Duration.ofMinutes(CANDLE_INTERVAL_MINUTES);

    private final MarketDataClient     marketDataClient;
    private final CandlestickRepository candlestickRepository;
    private final TradingConfig        tradingConfig;

    // One BarSeries per pair — updated on each cycle when candles are fetched.
    // ConcurrentHashMap because the trading loop and potential REST reads run on different threads.
    private final Map<String, BarSeries> barSeriesMap = new ConcurrentHashMap<>();

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Fetches the latest candles for the given pair from the Revolut API,
     * persists new/updated candles, rebuilds the BarSeries, and caches it.
     *
     * @param pair trading pair e.g. "BTC-EUR"
     * @return the freshly built BarSeries for this pair
     */
    public BarSeries fetchBarSeriesForPair(String pair) {
        log.info("Fetching candles for {} interval {}m", pair, CANDLE_INTERVAL_MINUTES);

        List<CandleResponse> candles = marketDataClient.getCandles(pair, CANDLE_INTERVAL_MINUTES);
        log.info("[{}] Received {} candles from API", pair, candles.size());

        persistCandles(candles, pair);

        BarSeries series = buildBarSeries(pair);
        barSeriesMap.put(pair, series);

        log.info("[{}] BarSeries built with {} bars", pair, series.getBarCount());
        return series;
    }

    /**
     * Returns the cached BarSeries for the given pair.
     * Falls back to reading from the DB if no in-memory series exists yet.
     */
    public BarSeries getBarSeriesForPair(String pair) {
        BarSeries cached = barSeriesMap.get(pair);
        if (cached != null) {
            return cached;
        }
        log.debug("[{}] No cached BarSeries — building from DB", pair);
        BarSeries series = buildBarSeriesFromDb(pair);
        barSeriesMap.put(pair, series);
        return series;
    }

    /**
     * Returns the current market price for the given pair.
     * Uses the last close from the cached BarSeries if available;
     * falls back to a live ticker API call (cheaper than a full candle fetch).
     */
    public BigDecimal getCurrentPriceForPair(String pair) {
        BarSeries series = barSeriesMap.get(pair);
        if (series != null && series.getBarCount() > 0) {
            return BigDecimal.valueOf(series.getLastBar().getClosePrice().doubleValue());
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
     * Returns the cached BarSeries for the primary pair.
     * Used by test endpoints and backward-compatible callers.
     */
    public BarSeries getBarSeries() {
        return getBarSeriesForPair(tradingConfig.primaryPair());
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

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

    private void persistCandles(List<CandleResponse> candles, String pair) {
        int newCount = 0;
        for (CandleResponse candle : candles) {
            // API returns 'start' (candle open time) in Unix epoch ms
            LocalDateTime timestamp = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(candle.start()), ZoneOffset.UTC);

            Optional<Candlestick> existing = candlestickRepository
                    .findByPairAndIntervalAndTimestamp(pair, CANDLE_INTERVAL_LABEL, timestamp);

            if (existing.isEmpty()) {
                Candlestick entity = Candlestick.builder()
                        .pair(pair)
                        .interval(CANDLE_INTERVAL_LABEL)
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
        log.debug("[{}] Candle sync: {} new, {} updated", pair, newCount, candles.size() - newCount);
    }

    private BarSeries buildBarSeries(String pair) {
        List<Candlestick> candles = candlestickRepository
                .findByPairAndIntervalOrderByTimestampAsc(pair, CANDLE_INTERVAL_LABEL);
        return buildBarSeriesFromCandles(pair, candles);
    }

    private BarSeries buildBarSeriesFromDb(String pair) {
        List<Candlestick> candles = candlestickRepository
                .findByPairAndIntervalOrderByTimestampAsc(pair, CANDLE_INTERVAL_LABEL);
        BarSeries series = buildBarSeriesFromCandles(pair, candles);
        log.info("[{}] BarSeries built from DB with {} bars", pair, series.getBarCount());
        return series;
    }

    private BarSeries buildBarSeriesFromCandles(String pair, List<Candlestick> candles) {
        BaseBarSeries series = new BaseBarSeries(pair);
        for (Candlestick c : candles) {
            ZonedDateTime endTime = c.getTimestamp().atZone(ZoneOffset.UTC).plus(BAR_DURATION);
            Bar bar = BaseBar.builder()
                    .timePeriod(BAR_DURATION)
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
