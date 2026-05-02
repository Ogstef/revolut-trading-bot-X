package com.stefo.revolut_trading_bot.strategy.impl

import com.stefo.revolut_trading_bot.config.MarketContextConfig
import com.stefo.revolut_trading_bot.market.MarketContext
import com.stefo.revolut_trading_bot.market.MarketContextService
import com.stefo.revolut_trading_bot.model.enums.SignalType
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import org.ta4j.core.BarSeries
import org.ta4j.core.BaseBar
import org.ta4j.core.BaseBarSeriesBuilder
import org.ta4j.core.num.DecimalNum
import spock.lang.Specification

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

class MarketContextStrategySpec extends Specification {

    MarketContextConfig config = new MarketContextConfig()
    MarketContextService service = Mock()
    BarSeries series = buildSeries(10)

    MarketContextStrategy strategy = new MarketContextStrategy(config, service)

    def "HOLDs with 'unavailable' reason when both upstreams returned null"() {
        given:
        service.getContext("BTC-EUR") >> new MarketContext("BTC-EUR", null, null, null, Instant.now())

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.HOLD
        signal.reason().contains("market context unavailable")
        signal.strategyType() == StrategyType.MARKET_CONTEXT
        signal.confidence() == new BigDecimal(50)
    }

    def "HOLDs when only F&G available (order book null)"() {
        given:
        service.getContext("BTC-EUR") >> new MarketContext("BTC-EUR", 10, "Extreme Fear", null, Instant.now())

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.HOLD
        signal.reason().contains("market context unavailable")
    }

    def "BUYs at extreme fear with strong bid pressure (high confidence)"() {
        given:
        service.getContext("BTC-EUR") >> new MarketContext(
                "BTC-EUR", 10, "Extreme Fear", new BigDecimal("2.0"), Instant.now())

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.BUY
        signal.confidence().intValueExact() >= 80
        signal.reason().contains("extreme fear")
        signal.emaShort() == new BigDecimal(10)        // F&G value in emaShort slot
        signal.emaLong()  == new BigDecimal("2.0")     // bid/ask ratio in emaLong slot
    }

    def "BUYs at threshold edge with low confidence"() {
        given:
        service.getContext("BTC-EUR") >> new MarketContext(
                "BTC-EUR", 24, "Extreme Fear", new BigDecimal("1.21"), Instant.now())

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.BUY
        signal.confidence().intValueExact() >= 50
        signal.confidence().intValueExact() <  60
    }

    def "HOLDs when F&G fails the BUY threshold even with strong bid pressure"() {
        given:
        service.getContext("BTC-EUR") >> new MarketContext(
                "BTC-EUR", 30, "Fear", new BigDecimal("2.0"), Instant.now())

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.HOLD
        signal.reason().contains("no contrarian signal")
    }

    def "HOLDs when bid/ask ratio fails the BUY threshold even with extreme fear"() {
        given:
        service.getContext("BTC-EUR") >> new MarketContext(
                "BTC-EUR", 10, "Extreme Fear", new BigDecimal("1.00"), Instant.now())

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.HOLD
    }

    def "SELLs at extreme greed with heavy ask pressure"() {
        given:
        service.getContext("BTC-EUR") >> new MarketContext(
                "BTC-EUR", 99, "Extreme Greed", new BigDecimal("0.10"), Instant.now())

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.SELL
        signal.confidence().intValueExact() >= 90
        signal.reason().contains("extreme greed")
    }

    def "SELLs at threshold edge with low confidence"() {
        given:
        service.getContext("BTC-EUR") >> new MarketContext(
                "BTC-EUR", 76, "Extreme Greed", new BigDecimal("0.82"), Instant.now())

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.SELL
        signal.confidence().intValueExact() >= 50
        signal.confidence().intValueExact() <  60
    }

    def "configurable thresholds: relaxing buy F&G ceiling lets a milder reading trigger"() {
        given:
        config.buyFearGreedMax = 40                       // relax from default 25
        service.getContext("BTC-EUR") >> new MarketContext(
                "BTC-EUR", 35, "Fear", new BigDecimal("1.30"), Instant.now())

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.BUY
    }

    private static BarSeries buildSeries(int bars) {
        def b = new BaseBarSeriesBuilder().withName("test").build()
        def now = ZonedDateTime.now()
        Duration period = Duration.ofHours(1)
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
