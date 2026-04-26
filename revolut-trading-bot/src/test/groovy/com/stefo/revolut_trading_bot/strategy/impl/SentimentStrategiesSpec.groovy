package com.stefo.revolut_trading_bot.strategy.impl

import com.stefo.revolut_trading_bot.config.SentimentConfig
import com.stefo.revolut_trading_bot.model.dto.SentimentScore
import com.stefo.revolut_trading_bot.model.enums.SentimentSource
import com.stefo.revolut_trading_bot.model.enums.SignalType
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import com.stefo.revolut_trading_bot.service.SentimentService
import org.ta4j.core.BarSeries
import org.ta4j.core.BaseBar
import org.ta4j.core.BaseBarSeriesBuilder
import org.ta4j.core.num.DecimalNum
import spock.lang.Specification

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

class SentimentStrategiesSpec extends Specification {

    SentimentConfig config
    SentimentService sentimentService = Mock()
    BarSeries series

    def setup() {
        config = new SentimentConfig()
        config.enabled = true
        config.strategies.reddit.buyThreshold      = new BigDecimal("0.35")
        config.strategies.reddit.sellThreshold     = new BigDecimal("-0.35")
        config.strategies.cryptopanic.buyThreshold  = new BigDecimal("0.35")
        config.strategies.cryptopanic.sellThreshold = new BigDecimal("-0.35")
        config.strategies.combined.buyThreshold    = new BigDecimal("0.40")
        config.strategies.combined.sellThreshold   = new BigDecimal("-0.40")
        config.strategies.combined.requireAgreement = true

        series = buildSeries(Duration.ofHours(1), 50)
    }

    def "RedditSentimentStrategy HOLDs when pipeline disabled"() {
        given:
        config.enabled = false
        def strategy = new RedditSentimentStrategy(config, sentimentService)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.HOLD
        signal.reason().contains("disabled")
        signal.strategyType() == StrategyType.REDDIT_SENTIMENT
        0 * sentimentService.scoreFor(_, _, _)
    }

    def "RedditSentimentStrategy BUYs when score exceeds threshold"() {
        given:
        sentimentService.scoreFor("BTC-EUR", "1h", SentimentSource.REDDIT) >>
                new SentimentScore("BTC-EUR", "1h", SentimentSource.REDDIT,
                        new BigDecimal("0.600"), 200L, 30L, 10, 240, Instant.now(), [])
        def strategy = new RedditSentimentStrategy(config, sentimentService)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.BUY
        signal.confidence().intValueExact() == 80   // 50 + 50 * 0.6
    }

    def "RedditSentimentStrategy SELLs when score drops below sell threshold"() {
        given:
        sentimentService.scoreFor("BTC-EUR", "1h", SentimentSource.REDDIT) >>
                new SentimentScore("BTC-EUR", "1h", SentimentSource.REDDIT,
                        new BigDecimal("-0.500"), 150L, 20L, 10, 240, Instant.now(), [])
        def strategy = new RedditSentimentStrategy(config, sentimentService)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.SELL
    }

    def "CryptoPanicStrategy emits HOLD with insufficient-sample reason"() {
        given:
        sentimentService.scoreFor("BTC-EUR", "1h", SentimentSource.CRYPTOPANIC) >>
                new SentimentScore("BTC-EUR", "1h", SentimentSource.CRYPTOPANIC,
                        null, 4L, 2L, 5, 240, Instant.now(), [])
        def strategy = new CryptoPanicStrategy(config, sentimentService)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.HOLD
        signal.reason().contains("insufficient")
        signal.strategyType() == StrategyType.CRYPTOPANIC_SENTIMENT
    }

    def "CombinedSentimentStrategy HOLDs when sources disagree (agreement filter on)"() {
        given:
        def reddit = new SentimentScore("BTC-EUR", "1h", SentimentSource.REDDIT,
                new BigDecimal("0.60"), 100L, 20L, 10, 240, Instant.now(), [])
        def crypto = new SentimentScore("BTC-EUR", "1h", SentimentSource.CRYPTOPANIC,
                new BigDecimal("-0.50"), 50L, 10L, 5, 240, Instant.now(), [])
        def combined = new SentimentScore("BTC-EUR", "1h", SentimentSource.COMBINED,
                new BigDecimal("0.45"), 150L, 30L, 10, 240, Instant.now(),
                [reddit, crypto])
        sentimentService.scoreFor("BTC-EUR", "1h", SentimentSource.COMBINED) >> combined
        def strategy = new CombinedSentimentStrategy(config, sentimentService)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.HOLD
        signal.reason().contains("disagree")
    }

    def "CombinedSentimentStrategy BUYs when both agree and above threshold"() {
        given:
        def reddit = new SentimentScore("BTC-EUR", "1h", SentimentSource.REDDIT,
                new BigDecimal("0.60"), 100L, 20L, 10, 240, Instant.now(), [])
        def crypto = new SentimentScore("BTC-EUR", "1h", SentimentSource.CRYPTOPANIC,
                new BigDecimal("0.40"), 80L, 10L, 5, 240, Instant.now(), [])
        def combined = new SentimentScore("BTC-EUR", "1h", SentimentSource.COMBINED,
                new BigDecimal("0.510"), 180L, 30L, 10, 240, Instant.now(),
                [reddit, crypto])
        sentimentService.scoreFor("BTC-EUR", "1h", SentimentSource.COMBINED) >> combined
        def strategy = new CombinedSentimentStrategy(config, sentimentService)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.BUY
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static BarSeries buildSeries(Duration period, int bars) {
        def b = new BaseBarSeriesBuilder().withName("test").build()
        def now = ZonedDateTime.now()
        for (int i = 0; i < bars; i++) {
            def end = now.plus(period.multipliedBy(i + 1))
            b.addBar(new BaseBar(
                    period, end,
                    DecimalNum.valueOf(100), DecimalNum.valueOf(110),
                    DecimalNum.valueOf(95), DecimalNum.valueOf(105),
                    DecimalNum.valueOf(0), DecimalNum.valueOf(0)))
        }
        return b
    }
}
