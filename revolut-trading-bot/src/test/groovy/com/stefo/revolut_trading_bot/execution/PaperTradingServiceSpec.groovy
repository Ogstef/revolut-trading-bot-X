package com.stefo.revolut_trading_bot.execution

import com.stefo.revolut_trading_bot.alert.AlertService
import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.model.entity.Position
import com.stefo.revolut_trading_bot.model.entity.Trade
import com.stefo.revolut_trading_bot.model.enums.OrderSide
import com.stefo.revolut_trading_bot.model.enums.OrderStatus
import com.stefo.revolut_trading_bot.model.enums.SignalType
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import com.stefo.revolut_trading_bot.repository.PositionRepository
import com.stefo.revolut_trading_bot.repository.TradeRepository
import com.stefo.revolut_trading_bot.risk.TakeProfitStopLossManager
import com.stefo.revolut_trading_bot.strategy.Signal
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal
import java.time.Instant

class PaperTradingServiceSpec extends Specification {

    PositionRepository      positionRepository = Mock()
    TradeRepository         tradeRepository    = Mock()
    TakeProfitStopLossManager tpslManager      = Mock()
    TradingConfig           config             = Stub()
    AlertService            alertService       = Mock()

    @Subject
    PaperTradingService service = new PaperTradingService(
            positionRepository, tradeRepository, tpslManager, config, alertService)

    def setup() {
        config.getRisk() >> buildRisk()
    }

    // ─── openPosition ─────────────────────────────────────────────────────────

    def "openPosition creates a BUY position with correct fields"() {
        given:
        def signal = buySignal("BTC-EUR", StrategyType.EMA_CROSSOVER)
        tpslManager.calculateTakeProfit(BigDecimal.valueOf(50_000)) >> BigDecimal.valueOf(52_500)
        tpslManager.calculateStopLoss(BigDecimal.valueOf(50_000))   >> BigDecimal.valueOf(48_500)
        positionRepository.save(_) >> { Position p -> p.id = 1L; p }
        tradeRepository.save(_) >> { Trade t -> t }

        when:
        def position = service.openPosition(signal, BigDecimal.valueOf(1_000), BigDecimal.valueOf(50_000))

        then:
        position.side == OrderSide.BUY
        position.pair == "BTC-EUR"
        position.entryPrice == BigDecimal.valueOf(50_000)
        position.strategyName == StrategyType.EMA_CROSSOVER
        position.status == OrderStatus.OPEN
        position.takeProfit == BigDecimal.valueOf(52_500)
        position.stopLoss == BigDecimal.valueOf(48_500)
    }

    def "openPosition calculates quantity as positionSizeEur divided by price"() {
        given:
        def signal = buySignal("BTC-EUR", StrategyType.EMA_CROSSOVER)
        tpslManager.calculateTakeProfit(_) >> BigDecimal.valueOf(52_500)
        tpslManager.calculateStopLoss(_)   >> BigDecimal.valueOf(48_500)
        positionRepository.save(_) >> { Position p -> p }
        tradeRepository.save(_) >> { Trade t -> t }

        when:
        def position = service.openPosition(signal, BigDecimal.valueOf(1_000), BigDecimal.valueOf(50_000))

        then: "1000 / 50000 = 0.02 BTC"
        position.quantity == new BigDecimal("0.02000000")
    }

    def "openPosition persists both position and trade"() {
        given:
        def signal = buySignal("BTC-EUR", StrategyType.EMA_CROSSOVER)
        tpslManager.calculateTakeProfit(_) >> BigDecimal.valueOf(52_500)
        tpslManager.calculateStopLoss(_)   >> BigDecimal.valueOf(48_500)
        positionRepository.save(_) >> { Position p -> p }
        tradeRepository.save(_) >> { Trade t -> t }

        when:
        service.openPosition(signal, BigDecimal.valueOf(1_000), BigDecimal.valueOf(50_000))

        then:
        1 * positionRepository.save(_)
        1 * tradeRepository.save(_)
        1 * alertService.positionOpened(_)
    }

    def "openPosition creates SELL position side for SELL signal"() {
        given:
        def signal = sellSignal("BTC-EUR", StrategyType.EMA_CROSSOVER)
        tpslManager.calculateTakeProfit(_) >> BigDecimal.valueOf(47_500)
        tpslManager.calculateStopLoss(_)   >> BigDecimal.valueOf(51_500)
        positionRepository.save(_) >> { Position p -> p }
        tradeRepository.save(_) >> { Trade t -> t }

        when:
        def position = service.openPosition(signal, BigDecimal.valueOf(1_000), BigDecimal.valueOf(50_000))

        then:
        position.side == OrderSide.SELL
    }

    // ─── closePosition ────────────────────────────────────────────────────────

    def "closePosition fills exit price, PnL and exit reason on trade"() {
        given:
        def position = openBuyPosition(BigDecimal.valueOf(50_000), BigDecimal.valueOf(0.02))
        def openTrade = openTrade(position, BigDecimal.valueOf(50_000), BigDecimal.valueOf(0.02))
        tradeRepository.findOpenTradeByPosition(position) >> Optional.of(openTrade)
        tradeRepository.save(_) >> { Trade t -> t }
        positionRepository.save(_) >> { Position p -> p }

        when:
        def result = service.closePosition(position, BigDecimal.valueOf(52_500), "TP_HIT")

        then:
        result.exitPrice == BigDecimal.valueOf(52_500)
        result.exitReason == "TP_HIT"
        result.pnl != null
        result.pnlPct != null
        result.closedAt != null
    }

    def "closePosition calculates positive PnL for profitable BUY"() {
        given: "bought at 50_000, exit at 52_500 → profit"
        def position = openBuyPosition(BigDecimal.valueOf(50_000), new BigDecimal("0.02000000"))
        def openTrade = openTrade(position, BigDecimal.valueOf(50_000), new BigDecimal("0.02000000"))
        tradeRepository.findOpenTradeByPosition(position) >> Optional.of(openTrade)
        tradeRepository.save(_) >> { Trade t -> t }
        positionRepository.save(_) >> { Position p -> p }

        when:
        def result = service.closePosition(position, BigDecimal.valueOf(52_500), "TP_HIT")

        then: "(52500 - 50000) * 0.02 = 50 EUR profit"
        result.pnl > BigDecimal.ZERO
        result.pnl == new BigDecimal("50.00000000")
    }

    def "closePosition calculates negative PnL for losing BUY"() {
        given: "bought at 50_000, exit at 48_500 → loss"
        def position = openBuyPosition(BigDecimal.valueOf(50_000), new BigDecimal("0.02000000"))
        def openTrade = openTrade(position, BigDecimal.valueOf(50_000), new BigDecimal("0.02000000"))
        tradeRepository.findOpenTradeByPosition(position) >> Optional.of(openTrade)
        tradeRepository.save(_) >> { Trade t -> t }
        positionRepository.save(_) >> { Position p -> p }

        when:
        def result = service.closePosition(position, BigDecimal.valueOf(48_500), "SL_HIT")

        then: "(48500 - 50000) * 0.02 = -30 EUR loss"
        result.pnl < BigDecimal.ZERO
        result.pnl == new BigDecimal("-30.00000000")
    }

    def "closePosition calculates positive PnL for profitable SELL (short)"() {
        given: "shorted at 50_000, exit at 47_500 → profit for short"
        def position = openSellPosition(BigDecimal.valueOf(50_000), new BigDecimal("0.02000000"))
        def openTrade = openTrade(position, BigDecimal.valueOf(50_000), new BigDecimal("0.02000000"))
        tradeRepository.findOpenTradeByPosition(position) >> Optional.of(openTrade)
        tradeRepository.save(_) >> { Trade t -> t }
        positionRepository.save(_) >> { Position p -> p }

        when:
        def result = service.closePosition(position, BigDecimal.valueOf(47_500), "TP_HIT")

        then: "short profit: (50000 - 47500) * 0.02 = 50 EUR"
        result.pnl > BigDecimal.ZERO
        result.pnl == new BigDecimal("50.00000000")
    }

    def "closePosition marks position as CLOSED"() {
        given:
        def position = openBuyPosition(BigDecimal.valueOf(50_000), BigDecimal.valueOf(0.02))
        def openTrade = openTrade(position, BigDecimal.valueOf(50_000), BigDecimal.valueOf(0.02))
        tradeRepository.findOpenTradeByPosition(position) >> Optional.of(openTrade)
        Position savedPosition = null
        positionRepository.save(_) >> { Position p -> savedPosition = p; p }
        tradeRepository.save(_) >> { Trade t -> t }

        when:
        service.closePosition(position, BigDecimal.valueOf(52_500), "TP_HIT")

        then:
        savedPosition.status == OrderStatus.CLOSED
        savedPosition.closedAt != null
        1 * alertService.positionClosed(_, _)
    }

    def "closePosition throws when no open trade found for position"() {
        given:
        def position = openBuyPosition(BigDecimal.valueOf(50_000), BigDecimal.valueOf(0.02))
        position.id = 99L
        tradeRepository.findOpenTradeByPosition(position) >> Optional.empty()

        when:
        service.closePosition(position, BigDecimal.valueOf(52_500), "TP_HIT")

        then:
        thrown(IllegalStateException)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static Signal buySignal(String pair, StrategyType strategyType) {
        new Signal(SignalType.BUY, BigDecimal.valueOf(75), "BUY signal",
                pair, strategyType, Instant.now(),
                null, null, null, BigDecimal.valueOf(50_000))
    }

    private static Signal sellSignal(String pair, StrategyType strategyType) {
        new Signal(SignalType.SELL, BigDecimal.valueOf(70), "SELL signal",
                pair, strategyType, Instant.now(),
                null, null, null, BigDecimal.valueOf(50_000))
    }

    private static Position openBuyPosition(BigDecimal entry, BigDecimal qty) {
        Position.builder()
                .pair("BTC-EUR")
                .side(OrderSide.BUY)
                .entryPrice(entry)
                .quantity(qty)
                .takeProfit(entry.multiply(BigDecimal.valueOf(1.05)))
                .stopLoss(entry.multiply(BigDecimal.valueOf(0.97)))
                .status(OrderStatus.OPEN)
                .strategyName(StrategyType.EMA_CROSSOVER)
                .build()
    }

    private static Position openSellPosition(BigDecimal entry, BigDecimal qty) {
        Position.builder()
                .pair("BTC-EUR")
                .side(OrderSide.SELL)
                .entryPrice(entry)
                .quantity(qty)
                .takeProfit(entry.multiply(BigDecimal.valueOf(0.95)))
                .stopLoss(entry.multiply(BigDecimal.valueOf(1.03)))
                .status(OrderStatus.OPEN)
                .strategyName(StrategyType.EMA_CROSSOVER)
                .build()
    }

    private static Trade openTrade(Position position, BigDecimal entry, BigDecimal qty) {
        Trade.builder()
                .position(position)
                .pair(position.pair)
                .side(position.side)
                .entryPrice(entry)
                .quantity(qty)
                .strategyName(StrategyType.EMA_CROSSOVER)
                .build()
    }

    private static TradingConfig.Risk buildRisk() {
        def r = new TradingConfig.Risk()
        r.takeProfitPct = BigDecimal.valueOf(5)
        r.stopLossPct   = BigDecimal.valueOf(3)
        r.maxConcurrentPositions = 3
        r.maxDailyLossPct = BigDecimal.valueOf(5)
        r.maxConsecutiveLosses = 5
        r.maxPositionPct = BigDecimal.valueOf(2)
        r
    }
}
