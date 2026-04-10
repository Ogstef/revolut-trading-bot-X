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
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataService {

    // 15-minute candles — interval must be an integer (minutes), not a string like "15m"
    private static final int CANDLE_INTERVAL_MINUTES = 15;
    private static final String CANDLE_INTERVAL_LABEL = "15m";
    private static final Duration BAR_DURATION = Duration.ofMinutes(CANDLE_INTERVAL_MINUTES);

    private final MarketDataClient marketDataClient;
    private final CandlestickRepository candlestickRepository;
    private final TradingConfig tradingConfig;

    private BarSeries barSeries;

    public BarSeries fetchAndBuildBarSeries() {
        String pair = tradingConfig.getPair();
        log.info("Fetching candles for {} interval {}m", pair, CANDLE_INTERVAL_MINUTES);

        List<CandleResponse> candles = marketDataClient.getCandles(pair, CANDLE_INTERVAL_MINUTES);
        log.info("Received {} candles from API", candles.size());

        persistCandles(candles, pair);
        barSeries = buildBarSeries(pair);

        log.info("BarSeries built with {} bars", barSeries.getBarCount());
        return barSeries;
    }

    public BarSeries getBarSeries() {
        if (barSeries == null) {
            return buildBarSeriesFromDb();
        }
        return barSeries;
    }

    /**
     * Returns the latest price. Uses in-memory BarSeries if available,
     * otherwise fetches a live ticker from the API (cheaper than a full candle fetch).
     */
    public BigDecimal getCurrentPrice() {
        if (barSeries != null && barSeries.getBarCount() > 0) {
            Bar lastBar = barSeries.getLastBar();
            return BigDecimal.valueOf(lastBar.getClosePrice().doubleValue());
        }
        return fetchCurrentPriceFromTicker();
    }

    private BigDecimal fetchCurrentPriceFromTicker() {
        String pair = tradingConfig.getPair();
        List<TickerResponse> tickers = marketDataClient.getTickers(pair);
        if (tickers.isEmpty()) {
            log.warn("No ticker data available for {}", pair);
            return BigDecimal.ZERO;
        }
        // Use mid-price (average of best bid/ask) as a neutral current price reference
        BigDecimal mid = tickers.getFirst().mid();
        log.debug("Current price from ticker mid: {}", mid);
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
        log.debug("Candle sync: {} new, {} updated", newCount, candles.size() - newCount);
    }

    private BarSeries buildBarSeries(String pair) {
        List<Candlestick> candles = candlestickRepository
                .findByPairAndIntervalOrderByTimestampAsc(pair, CANDLE_INTERVAL_LABEL);
        return buildBarSeriesFromCandles(candles);
    }

    private BarSeries buildBarSeriesFromDb() {
        String pair = tradingConfig.getPair();
        List<Candlestick> candles = candlestickRepository
                .findByPairAndIntervalOrderByTimestampAsc(pair, CANDLE_INTERVAL_LABEL);
        barSeries = buildBarSeriesFromCandles(candles);
        log.info("BarSeries built from DB with {} bars", barSeries.getBarCount());
        return barSeries;
    }

    private BarSeries buildBarSeriesFromCandles(List<Candlestick> candles) {
        BaseBarSeries series = new BaseBarSeries(tradingConfig.getPair());
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
