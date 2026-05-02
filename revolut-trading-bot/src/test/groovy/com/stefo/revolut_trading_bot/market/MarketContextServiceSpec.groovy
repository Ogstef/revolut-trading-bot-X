package com.stefo.revolut_trading_bot.market

import com.stefo.revolut_trading_bot.model.dto.FearGreedResponse
import com.stefo.revolut_trading_bot.model.dto.OrderBookResponse
import com.stefo.revolut_trading_bot.service.FearGreedService
import spock.lang.Specification

import java.math.BigDecimal

class MarketContextServiceSpec extends Specification {

    FearGreedService fearGreed = Mock()
    MarketDataClient market    = Mock()

    MarketContextService service = new MarketContextService(fearGreed, market)

    def "context populated with both inputs when fetches succeed"() {
        given:
        fearGreed.get() >> new FearGreedResponse(15, "Extreme Fear", 1_700_000_000L)
        market.getOrderBook("BTC-EUR", 5) >> new OrderBookResponse(
                [level("100", "2"), level("99", "3")],
                [level("101", "1"), level("102", "1")])

        when:
        def ctx = service.getContext("BTC-EUR")

        then:
        ctx.pair() == "BTC-EUR"
        ctx.fearGreedValue() == 15
        ctx.fearGreedClassification() == "Extreme Fear"
        ctx.bidAskRatio() == new BigDecimal("2.5000")          // (2+3)/(1+1)
        ctx.snapshotAt() != null
    }

    def "F&G failure leaves only orderbook populated — never throws"() {
        given:
        fearGreed.get() >> { throw new RuntimeException("boom") }
        market.getOrderBook("ETH-EUR", 5) >> new OrderBookResponse(
                [level("1", "1")], [level("2", "1")])

        when:
        def ctx = service.getContext("ETH-EUR")

        then:
        ctx.fearGreedValue() == null
        ctx.fearGreedClassification() == null
        ctx.bidAskRatio() == new BigDecimal("1.0000")
    }

    def "order book failure leaves only F&G populated — never throws"() {
        given:
        fearGreed.get() >> new FearGreedResponse(80, "Extreme Greed", 1L)
        market.getOrderBook("SOL-EUR", 5) >> { throw new RuntimeException("rate limit") }

        when:
        def ctx = service.getContext("SOL-EUR")

        then:
        ctx.fearGreedValue() == 80
        ctx.bidAskRatio() == null
    }

    def "both failures still return a non-null context"() {
        given:
        fearGreed.get() >> { throw new RuntimeException("dns") }
        market.getOrderBook(_, _) >> { throw new RuntimeException("dns") }

        when:
        def ctx = service.getContext("BTC-EUR")

        then:
        ctx != null
        ctx.fearGreedValue() == null
        ctx.bidAskRatio() == null
    }

    def "cache returns the same instance within TTL"() {
        given:
        fearGreed.get() >> new FearGreedResponse(50, "Neutral", 1L)
        market.getOrderBook("BTC-EUR", 5) >> new OrderBookResponse(
                [level("1", "1")], [level("2", "1")])

        when:
        def first  = service.getContext("BTC-EUR")
        def second = service.getContext("BTC-EUR")

        then:
        first.is(second)
        // Each upstream is consulted exactly once for the two getContext calls.
        1 * fearGreed.get() >> new FearGreedResponse(50, "Neutral", 1L)
        1 * market.getOrderBook("BTC-EUR", 5) >> new OrderBookResponse(
                [level("1", "1")], [level("2", "1")])
    }

    def "empty asks list yields null ratio (no division by zero)"() {
        given:
        fearGreed.get() >> new FearGreedResponse(50, "Neutral", 1L)
        market.getOrderBook("BTC-EUR", 5) >> new OrderBookResponse(
                [level("1", "1")], [])

        when:
        def ctx = service.getContext("BTC-EUR")

        then:
        ctx.bidAskRatio() == null
    }

    private static OrderBookResponse.PriceLevel level(String price, String qty) {
        new OrderBookResponse.PriceLevel(new BigDecimal(price), new BigDecimal(qty))
    }
}
