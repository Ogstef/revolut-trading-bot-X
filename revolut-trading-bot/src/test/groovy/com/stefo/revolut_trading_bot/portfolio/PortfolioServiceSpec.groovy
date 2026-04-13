package com.stefo.revolut_trading_bot.portfolio

import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.model.entity.Position
import com.stefo.revolut_trading_bot.model.enums.OrderSide
import com.stefo.revolut_trading_bot.model.enums.OrderStatus
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import com.stefo.revolut_trading_bot.repository.PositionRepository
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal

class PortfolioServiceSpec extends Specification {

    PositionRepository positionRepository = Mock()
    TradingConfig      config             = buildConfig()

    @Subject
    PortfolioService service = new PortfolioService(positionRepository, config)

    // ─── getSnapshot ─────────────────────────────────────────────────────────

    def "getSnapshot returns zero values when no open positions"() {
        given:
        positionRepository.findByStatus(OrderStatus.OPEN) >> []

        when:
        def snapshot = service.getSnapshot(BigDecimal.valueOf(50_000))

        then:
        snapshot.openPositions()    == 0
        snapshot.totalInvested()    == new BigDecimal("0.00")
        snapshot.unrealisedPnl()    == new BigDecimal("0.00")
        snapshot.unrealisedPnlPct() == BigDecimal.ZERO
        snapshot.pair()             == "BTC-EUR"
        snapshot.asOf()             != null
    }

    def "getSnapshot calculates correct unrealised PnL for BUY position (price up)"() {
        given: "Bought 0.02 BTC at 50_000, current price 52_500 → unrealised profit"
        def position = buyPosition(BigDecimal.valueOf(50_000), new BigDecimal("0.02"))
        positionRepository.findByStatus(OrderStatus.OPEN) >> [position]

        when:
        def snapshot = service.getSnapshot(BigDecimal.valueOf(52_500))

        then:
        snapshot.openPositions() == 1
        snapshot.totalInvested() == new BigDecimal("1000.00")    // 50_000 * 0.02
        snapshot.unrealisedPnl() == new BigDecimal("50.00")      // (52500-50000)*0.02
        snapshot.unrealisedPnlPct() > BigDecimal.ZERO
    }

    def "getSnapshot calculates correct unrealised PnL for BUY position (price down)"() {
        given: "Bought 0.02 BTC at 50_000, current price 48_500 → unrealised loss"
        def position = buyPosition(BigDecimal.valueOf(50_000), new BigDecimal("0.02"))
        positionRepository.findByStatus(OrderStatus.OPEN) >> [position]

        when:
        def snapshot = service.getSnapshot(BigDecimal.valueOf(48_500))

        then:
        snapshot.unrealisedPnl() == new BigDecimal("-30.00")     // (48500-50000)*0.02
        snapshot.unrealisedPnlPct() < BigDecimal.ZERO
    }

    def "getSnapshot calculates positive unrealised PnL for profitable SELL (short)"() {
        given: "Shorted 0.02 BTC at 50_000, current price 47_500 → profit for short"
        def position = sellPosition(BigDecimal.valueOf(50_000), new BigDecimal("0.02"))
        positionRepository.findByStatus(OrderStatus.OPEN) >> [position]

        when:
        def snapshot = service.getSnapshot(BigDecimal.valueOf(47_500))

        then:
        snapshot.unrealisedPnl() == new BigDecimal("50.00")    // (50000-47500)*0.02
    }

    def "getSnapshot aggregates PnL across multiple open positions"() {
        given: "Two BUY positions, both in profit"
        def pos1 = buyPosition(BigDecimal.valueOf(50_000), new BigDecimal("0.01"))  // invested=500
        def pos2 = buyPosition(BigDecimal.valueOf(48_000), new BigDecimal("0.01"))  // invested=480
        positionRepository.findByStatus(OrderStatus.OPEN) >> [pos1, pos2]

        when:
        def snapshot = service.getSnapshot(BigDecimal.valueOf(52_000))

        then:
        snapshot.openPositions() == 2
        snapshot.totalInvested() == new BigDecimal("980.00")    // 500 + 480
        // pos1 PnL: (52000-50000)*0.01 = 20
        // pos2 PnL: (52000-48000)*0.01 = 40
        snapshot.unrealisedPnl() == new BigDecimal("60.00")
    }

    def "getSnapshot includes current price in result"() {
        given:
        positionRepository.findByStatus(OrderStatus.OPEN) >> []

        when:
        def snapshot = service.getSnapshot(BigDecimal.valueOf(60_000))

        then:
        snapshot.currentPrice() == BigDecimal.valueOf(60_000)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static Position buyPosition(BigDecimal entry, BigDecimal qty) {
        Position.builder()
                .pair("BTC-EUR")
                .side(OrderSide.BUY)
                .entryPrice(entry)
                .quantity(qty)
                .takeProfit(entry * BigDecimal.valueOf(1.05))
                .stopLoss(entry * BigDecimal.valueOf(0.97))
                .status(OrderStatus.OPEN)
                .strategyName(StrategyType.EMA_CROSSOVER)
                .build()
    }

    private static Position sellPosition(BigDecimal entry, BigDecimal qty) {
        Position.builder()
                .pair("BTC-EUR")
                .side(OrderSide.SELL)
                .entryPrice(entry)
                .quantity(qty)
                .takeProfit(entry * BigDecimal.valueOf(0.95))
                .stopLoss(entry * BigDecimal.valueOf(1.03))
                .status(OrderStatus.OPEN)
                .strategyName(StrategyType.EMA_CROSSOVER)
                .build()
    }

    private static TradingConfig buildConfig() {
        def cfg = new TradingConfig()
        cfg.pairs = ["BTC-EUR"]
        cfg.mode  = "PAPER"
        cfg.paperBalance = BigDecimal.valueOf(10_000)
        cfg.pollingIntervalSeconds = 30
        cfg.risk     = new TradingConfig.Risk()
        cfg.strategy = new TradingConfig.Strategy()
        cfg
    }
}
