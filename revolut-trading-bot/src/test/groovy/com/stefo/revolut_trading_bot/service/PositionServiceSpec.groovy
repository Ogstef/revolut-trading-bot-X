package com.stefo.revolut_trading_bot.service

import com.stefo.revolut_trading_bot.market.MarketDataService
import com.stefo.revolut_trading_bot.model.entity.Position
import com.stefo.revolut_trading_bot.model.enums.OrderSide
import com.stefo.revolut_trading_bot.model.enums.OrderStatus
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import com.stefo.revolut_trading_bot.repository.PositionRepository
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal

class PositionServiceSpec extends Specification {

    PositionRepository repository       = Mock()
    MarketDataService  marketDataService = Mock()

    @Subject
    PositionService service = new PositionService(repository, marketDataService)

    // ─── getPositions ─────────────────────────────────────────────────────────

    def "getPositions filters by pair when provided"() {
        given:
        def position = openBuyPosition("BTC-EUR")
        repository.findByPairAndStatus("BTC-EUR", OrderStatus.OPEN) >> [position]
        marketDataService.getCurrentPriceForPair("BTC-EUR") >> BigDecimal.valueOf(52_000)

        when:
        def views = service.getPositions("BTC-EUR")

        then:
        views.size() == 1
        views[0].pair() == "BTC-EUR"
        views[0].currentPrice() == BigDecimal.valueOf(52_000)
    }

    def "getPositions returns all open positions when pair is null"() {
        given:
        def btcPos = openBuyPosition("BTC-EUR")
        def ethPos = openBuyPosition("ETH-EUR")
        repository.findByStatus(OrderStatus.OPEN) >> [btcPos, ethPos]
        marketDataService.getCurrentPriceForPair("BTC-EUR") >> BigDecimal.valueOf(52_000)
        marketDataService.getCurrentPriceForPair("ETH-EUR") >> BigDecimal.valueOf(3_000)

        when:
        def views = service.getPositions(null)

        then:
        views.size() == 2
    }

    def "getPositions returns empty list when no open positions"() {
        given:
        repository.findByPairAndStatus("BTC-EUR", OrderStatus.OPEN) >> []

        when:
        def views = service.getPositions("BTC-EUR")

        then:
        views.isEmpty()
    }

    def "getPositions maps unrealised PnL using current market price"() {
        given: "bought at 50_000, current price 52_500 → 50 EUR unrealised profit"
        def position = Position.builder()
                .pair("BTC-EUR")
                .side(OrderSide.BUY)
                .entryPrice(BigDecimal.valueOf(50_000))
                .quantity(new BigDecimal("0.02"))
                .takeProfit(BigDecimal.valueOf(52_500))
                .stopLoss(BigDecimal.valueOf(48_500))
                .status(OrderStatus.OPEN)
                .strategyName(StrategyType.EMA_CROSSOVER)
                .build()
        repository.findByPairAndStatus("BTC-EUR", OrderStatus.OPEN) >> [position]
        marketDataService.getCurrentPriceForPair("BTC-EUR") >> BigDecimal.valueOf(52_500)

        when:
        def views = service.getPositions("BTC-EUR")

        then:
        views[0].unrealisedPnl() == new BigDecimal("50.00")
    }

    // ─── getOpenPositions ─────────────────────────────────────────────────────

    def "getOpenPositions delegates to repository with status, pair and strategy"() {
        given:
        def expected = [openBuyPosition("ETH-EUR")]
        repository.findByStatusAndPairAndStrategyName(OrderStatus.OPEN, "ETH-EUR", StrategyType.MACD) >> expected

        when:
        def result = service.getOpenPositions("ETH-EUR", StrategyType.MACD)

        then:
        result == expected
    }

    // ─── getPairs ─────────────────────────────────────────────────────────────

    def "getPairs returns pair-filtered positions when pair is specified"() {
        given:
        def expected = [openBuyPosition("BTC-EUR")]
        repository.findByPairAndStatus("BTC-EUR", OrderStatus.OPEN) >> expected

        when:
        def result = service.getPairs("BTC-EUR")

        then:
        result == expected
    }

    def "getPairs returns all open positions when pair is null"() {
        given:
        def expected = [openBuyPosition("BTC-EUR"), openBuyPosition("ETH-EUR")]
        repository.findByStatus(OrderStatus.OPEN) >> expected

        when:
        def result = service.getPairs(null)

        then:
        result == expected
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static Position openBuyPosition(String pair) {
        Position.builder()
                .pair(pair)
                .side(OrderSide.BUY)
                .entryPrice(BigDecimal.valueOf(50_000))
                .quantity(new BigDecimal("0.02"))
                .takeProfit(BigDecimal.valueOf(52_500))
                .stopLoss(BigDecimal.valueOf(48_500))
                .status(OrderStatus.OPEN)
                .strategyName(StrategyType.EMA_CROSSOVER)
                .build()
    }
}
