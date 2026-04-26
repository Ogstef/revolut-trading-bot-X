package com.stefo.revolut_trading_bot.strategy.impl;

import com.stefo.revolut_trading_bot.config.SentimentConfig;
import com.stefo.revolut_trading_bot.model.dto.SentimentScore;
import com.stefo.revolut_trading_bot.model.enums.SentimentSource;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.service.SentimentService;
import com.stefo.revolut_trading_bot.strategy.Signal;
import com.stefo.revolut_trading_bot.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Trades the CryptoPanic-only sentiment aggregate for the pair.
 * Score comes from scraped vote counts (no LLM call), derived in
 * {@code CryptoPanicIngestService}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CryptoPanicStrategy implements TradingStrategy {

    private final SentimentConfig config;
    private final SentimentService sentimentService;

    @Override
    public StrategyType strategyType() {
        return StrategyType.CRYPTOPANIC_SENTIMENT;
    }

    @Override
    public Signal evaluate(BarSeries series, String pair) {
        Instant now = Instant.now();
        BigDecimal price = SentimentStrategyHelpers.lastClose(series);

        if (!config.isEnabled()) {
            return SentimentStrategyHelpers.disabled(strategyType(), pair, price, now);
        }

        Optional<String> interval = BarSeriesIntervalDetector.labelFromSeries(series);
        if (interval.isEmpty()) {
            return SentimentStrategyHelpers.missingInterval(strategyType(), pair, price, now);
        }

        SentimentScore score = sentimentService.scoreFor(pair, interval.get(), SentimentSource.CRYPTOPANIC);
        SentimentConfig.StrategyThresholds t = config.getStrategies().getCryptopanic();
        return SentimentSignalBuilder.build(
                score, strategyType(),
                t.getBuyThreshold(), t.getSellThreshold(),
                false,
                price, now);
    }
}
