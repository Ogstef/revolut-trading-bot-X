package com.stefo.revolut_trading_bot.market;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.dto.CandleResponse;
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

    private static final String CANDLE_INTERVAL = "15m";
    private static final Duration BAR_DURATION = Duration.ofMinutes(15);

    private final MarketDataClient marketDataClient;
    private final CandlestickRepository candlestickRepository;
    private final TradingConfig tradingConfig;

    private BarSeries barSeries;

    public BarSeries fetchAndBuildBarSeries() {
        String pair = tradingConfig.getPair();
        log.info("Fetching candles for {} interval {}", pair, CANDLE_INTERVAL);

        List<CandleResponse> candles = marketDataClient.getCandles(pair, CANDLE_INTERVAL);
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

    public BigDecimal getCurrentPrice() {
        if (barSeries != null && barSeries.getBarCount() > 0) {
            Bar lastBar = barSeries.getLastBar();
            return BigDecimal.valueOf(lastBar.getClosePrice().doubleValue());
        }
        return fetchCurrentPriceFromApi();
    }

    private BigDecimal fetchCurrentPriceFromApi() {
        String pair = tradingConfig.getPair();
        var trades = marketDataClient.getPublicTrades(pair);
        if (trades.isEmpty()) {
            log.warn("No public trades available for {}", pair);
            return BigDecimal.ZERO;
        }
        return trades.getFirst().price();
    }

    private void persistCandles(List<CandleResponse> candles, String pair) {
        int newCount = 0;
        for (CandleResponse candle : candles) {
            LocalDateTime timestamp = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(candle.timestamp()), ZoneOffset.UTC);

            Optional<Candlestick> existing = candlestickRepository
                    .findByPairAndIntervalAndTimestamp(pair, CANDLE_INTERVAL, timestamp);

            if (existing.isEmpty()) {
                Candlestick entity = Candlestick.builder()
                        .pair(pair)
                        .interval(CANDLE_INTERVAL)
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
                Candlestick entity = existing.get();
                entity.setClosePrice(candle.close());
                entity.setHighPrice(candle.high());
                entity.setLowPrice(candle.low());
                entity.setVolume(candle.volume());
                candlestickRepository.save(entity);
            }
        }
        log.debug("Persisted {} new candles, updated {} existing", newCount, candles.size() - newCount);
    }

    private BarSeries buildBarSeries(String pair) {
        List<Candlestick> candles = candlestickRepository
                .findByPairAndIntervalOrderByTimestampAsc(pair, CANDLE_INTERVAL);
        return buildBarSeriesFromCandles(candles);
    }

    private BarSeries buildBarSeriesFromDb() {
        String pair = tradingConfig.getPair();
        List<Candlestick> candles = candlestickRepository
                .findByPairAndIntervalOrderByTimestampAsc(pair, CANDLE_INTERVAL);
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
