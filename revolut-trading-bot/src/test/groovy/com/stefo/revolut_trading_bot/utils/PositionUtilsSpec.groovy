package com.stefo.revolut_trading_bot.utils

import com.stefo.revolut_trading_bot.model.entity.Position
import com.stefo.revolut_trading_bot.model.enums.OrderSide
import com.stefo.revolut_trading_bot.model.enums.OrderStatus
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import spock.lang.Specification

import java.math.BigDecimal
import java.time.LocalDateTime

class PositionUtilsSpec extends Specification {

    // ─── toPositionView ── BUY positions ─────────────────────────────────────

    def "toPositionView: positive PnL when current price is above entry (BUY)"() {
        given:
        def position = buyPosition(BigDecimal.valueOf(50_000), new BigDecimal("0.02"))

        when:
        def view = PositionUtils.toPositionView(position, BigDecimal.valueOf(52_500))

        then: "(52500 - 50000) * 0.02 = 50 EUR"
        view.unrealisedPnl() == new BigDecimal("50.00")
        view.unrealisedPnlPct() > BigDecimal.ZERO
    }

    def "toPositionView: negative PnL when current price is below entry (BUY)"() {
        given:
        def position = buyPosition(BigDecimal.valueOf(50_000), new BigDecimal("0.02"))

        when:
        def view = PositionUtils.toPositionView(position, BigDecimal.valueOf(48_500))

        then: "(48500 - 50000) * 0.02 = -30 EUR"
        view.unrealisedPnl() == new BigDecimal("-30.00")
        view.unrealisedPnlPct() < BigDecimal.ZERO
    }

    def "toPositionView: zero PnL when price equals entry"() {
        given:
        def position = buyPosition(BigDecimal.valueOf(50_000), new BigDecimal("0.02"))

        when:
        def view = PositionUtils.toPositionView(position, BigDecimal.valueOf(50_000))

        then:
        view.unrealisedPnl() == BigDecimal.ZERO.setScale(2)
        view.unrealisedPnlPct() == BigDecimal.ZERO
    }

    // ─── toPositionView — SELL (short) positions ──────────────────────────────

    def "toPositionView: positive PnL when price falls below entry (SELL)"() {
        given:
        def position = sellPosition(BigDecimal.valueOf(50_000), new BigDecimal("0.02"))

        when:
        def view = PositionUtils.toPositionView(position, BigDecimal.valueOf(47_500))

        then: "short profit: (50000 - 47500) * 0.02 = 50 EUR"
        view.unrealisedPnl() == new BigDecimal("50.00")
        view.unrealisedPnlPct() > BigDecimal.ZERO
    }

    def "toPositionView: negative PnL when price rises above entry (SELL)"() {
        given:
        def position = sellPosition(BigDecimal.valueOf(50_000), new BigDecimal("0.02"))

        when:
        def view = PositionUtils.toPositionView(position, BigDecimal.valueOf(52_500))

        then: "short loss: (50000 - 52500) * 0.02 = -50 EUR"
        view.unrealisedPnl() == new BigDecimal("-50.00")
    }

    // ─── toPositionView — field population ───────────────────────────────────

    def "toPositionView populates all fields from position"() {
        given:
        def openedAt = LocalDateTime.of(2025, 1, 15, 10, 0)
        def position = Position.builder()
                .id(42L)
                .pair("BTC-EUR")
                .side(OrderSide.BUY)
                .entryPrice(BigDecimal.valueOf(50_000))
                .quantity(new BigDecimal("0.02"))
                .takeProfit(BigDecimal.valueOf(52_500))
                .stopLoss(BigDecimal.valueOf(48_500))
                .status(OrderStatus.OPEN)
                .strategyName(StrategyType.EMA_CROSSOVER)
                .signalReason("EMA9 crossed above EMA21")
                .openedAt(openedAt)
                .build()

        when:
        def view = PositionUtils.toPositionView(position, BigDecimal.valueOf(51_000))

        then:
        view.id()           == 42L
        view.pair()         == "BTC-EUR"
        view.side()         == "BUY"
        view.entryPrice()   == BigDecimal.valueOf(50_000)
        view.quantity()     == new BigDecimal("0.02")
        view.takeProfit()   == BigDecimal.valueOf(52_500)
        view.stopLoss()     == BigDecimal.valueOf(48_500)
        view.currentPrice() == BigDecimal.valueOf(51_000)
        view.signalReason() == "EMA9 crossed above EMA21"
        view.openedAt()     == openedAt
    }

    def "toPositionView PnlPct calculation is proportional to entry value"() {
        given: "buy 1 BTC at 100_000, price moves to 105_000 → 5% gain"
        def position = buyPosition(BigDecimal.valueOf(100_000), BigDecimal.ONE)

        when:
        def view = PositionUtils.toPositionView(position, BigDecimal.valueOf(105_000))

        then:
        view.unrealisedPnl() == new BigDecimal("5000.00")
        view.unrealisedPnlPct() == new BigDecimal("5.00")
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static Position buyPosition(BigDecimal entry, BigDecimal qty) {
        Position.builder()
                .pair("BTC-EUR")
                .side(OrderSide.BUY)
                .entryPrice(entry)
                .quantity(qty)
                .takeProfit(entry.multiply(BigDecimal.valueOf(1.05)))
                .stopLoss(entry.multiply(BigDecimal.valueOf(0.97)))
                .status(OrderStatus.OPEN)
                .strategyName(StrategyType.EMA_CROSSOVER)
                .signalReason("test")
                .openedAt(LocalDateTime.now())
                .build()
    }

    private static Position sellPosition(BigDecimal entry, BigDecimal qty) {
        Position.builder()
                .pair("BTC-EUR")
                .side(OrderSide.SELL)
                .entryPrice(entry)
                .quantity(qty)
                .takeProfit(entry.multiply(BigDecimal.valueOf(0.95)))
                .stopLoss(entry.multiply(BigDecimal.valueOf(1.03)))
                .status(OrderStatus.OPEN)
                .strategyName(StrategyType.EMA_CROSSOVER)
                .signalReason("test")
                .openedAt(LocalDateTime.now())
                .build()
    }
}
