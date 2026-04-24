package com.stefo.revolut_trading_bot.risk

import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.model.entity.Position
import com.stefo.revolut_trading_bot.model.enums.OrderSide
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

import java.math.BigDecimal

class TakeProfitStopLossManagerSpec extends Specification {

    TradingConfig config = buildConfig(BigDecimal.valueOf(5), BigDecimal.valueOf(3))

    @Subject
    TakeProfitStopLossManager manager = new TakeProfitStopLossManager(config)

    // ─── calculateTakeProfit ─────────────────────────────────────────────────

    def "calculateTakeProfit returns entry × 1.05 for 5% config"() {
        when:
        def tp = manager.calculateTakeProfit(BigDecimal.valueOf(100_000))

        then:
        tp == new BigDecimal("105000.00000000")
    }

    def "calculateTakeProfit scales result to 8 decimal places"() {
        when:
        def tp = manager.calculateTakeProfit(BigDecimal.valueOf(30_000))

        then:
        tp.scale() == 8
    }

    // ─── calculateStopLoss ───────────────────────────────────────────────────

    def "calculateStopLoss returns entry × 0.97 for 3% config"() {
        when:
        def sl = manager.calculateStopLoss(BigDecimal.valueOf(100_000))

        then:
        sl == new BigDecimal("97000.00000000")
    }

    def "calculateStopLoss is always below entry price"() {
        when:
        def sl = manager.calculateStopLoss(BigDecimal.valueOf(50_000))

        then:
        sl < BigDecimal.valueOf(50_000)
    }

    // ─── checkExitCondition — BUY (long) ─────────────────────────────────────

    def "BUY position: returns TP_HIT when price equals takeProfit"() {
        given:
        def position = buyPosition(BigDecimal.valueOf(100_000), BigDecimal.valueOf(105_000), BigDecimal.valueOf(97_000))

        when:
        def result = manager.checkExitCondition(position, BigDecimal.valueOf(105_000))

        then:
        result.isPresent()
        result.get() == TakeProfitStopLossManager.EXIT_TAKE_PROFIT
    }

    def "BUY position: returns TP_HIT when price exceeds takeProfit"() {
        given:
        def position = buyPosition(BigDecimal.valueOf(100_000), BigDecimal.valueOf(105_000), BigDecimal.valueOf(97_000))

        when:
        def result = manager.checkExitCondition(position, BigDecimal.valueOf(110_000))

        then:
        result.isPresent()
        result.get() == TakeProfitStopLossManager.EXIT_TAKE_PROFIT
    }

    def "BUY position: returns SL_HIT when price equals stopLoss"() {
        given:
        def position = buyPosition(BigDecimal.valueOf(100_000), BigDecimal.valueOf(105_000), BigDecimal.valueOf(97_000))

        when:
        def result = manager.checkExitCondition(position, BigDecimal.valueOf(97_000))

        then:
        result.isPresent()
        result.get() == TakeProfitStopLossManager.EXIT_STOP_LOSS
    }

    def "BUY position: returns SL_HIT when price falls below stopLoss"() {
        given:
        def position = buyPosition(BigDecimal.valueOf(100_000), BigDecimal.valueOf(105_000), BigDecimal.valueOf(97_000))

        when:
        def result = manager.checkExitCondition(position, BigDecimal.valueOf(95_000))

        then:
        result.isPresent()
        result.get() == TakeProfitStopLossManager.EXIT_STOP_LOSS
    }

    def "BUY position: returns empty when price is between SL and TP"() {
        given:
        def position = buyPosition(BigDecimal.valueOf(100_000), BigDecimal.valueOf(105_000), BigDecimal.valueOf(97_000))

        when:
        def result = manager.checkExitCondition(position, BigDecimal.valueOf(101_000))

        then:
        !result.isPresent()
    }

    // ─── checkExitCondition — SELL (short) ───────────────────────────────────

    def "SELL position: returns TP_HIT when price drops to takeProfit"() {
        given: "short: TP is below entry, SL is above entry"
        def position = sellPosition(BigDecimal.valueOf(100_000), BigDecimal.valueOf(95_000), BigDecimal.valueOf(103_000))

        when:
        def result = manager.checkExitCondition(position, BigDecimal.valueOf(95_000))

        then:
        result.isPresent()
        result.get() == TakeProfitStopLossManager.EXIT_TAKE_PROFIT
    }

    def "SELL position: returns TP_HIT when price drops below takeProfit"() {
        given:
        def position = sellPosition(BigDecimal.valueOf(100_000), BigDecimal.valueOf(95_000), BigDecimal.valueOf(103_000))

        when:
        def result = manager.checkExitCondition(position, BigDecimal.valueOf(90_000))

        then:
        result.isPresent()
        result.get() == TakeProfitStopLossManager.EXIT_TAKE_PROFIT
    }

    def "SELL position: returns SL_HIT when price rises to stopLoss"() {
        given:
        def position = sellPosition(BigDecimal.valueOf(100_000), BigDecimal.valueOf(95_000), BigDecimal.valueOf(103_000))

        when:
        def result = manager.checkExitCondition(position, BigDecimal.valueOf(103_000))

        then:
        result.isPresent()
        result.get() == TakeProfitStopLossManager.EXIT_STOP_LOSS
    }

    def "SELL position: returns SL_HIT when price rises above stopLoss"() {
        given:
        def position = sellPosition(BigDecimal.valueOf(100_000), BigDecimal.valueOf(95_000), BigDecimal.valueOf(103_000))

        when:
        def result = manager.checkExitCondition(position, BigDecimal.valueOf(110_000))

        then:
        result.isPresent()
        result.get() == TakeProfitStopLossManager.EXIT_STOP_LOSS
    }

    def "SELL position: returns empty when price between TP and SL"() {
        given:
        def position = sellPosition(BigDecimal.valueOf(100_000), BigDecimal.valueOf(95_000), BigDecimal.valueOf(103_000))

        when:
        def result = manager.checkExitCondition(position, BigDecimal.valueOf(99_000))

        then:
        !result.isPresent()
    }

    // ─── Liquidation (leveraged) ─────────────────────────────────────────────

    @Unroll
    def "liquidation price for #leverage× long ≈ entry × (1 − 1/leverage + mm%)"() {
        when:
        def liq = manager.calculateLiquidationPrice(
                BigDecimal.valueOf(100), OrderSide.BUY, leverage, new BigDecimal("0.5"))

        then: "within 0.1 EUR of expected"
        (liq - expected).abs() < new BigDecimal("0.1")

        where:
        leverage || expected
        3        || BigDecimal.valueOf(67.167)   // 100 × (1 − 0.3333 + 0.005)
        5        || BigDecimal.valueOf(80.5)     // 100 × (1 − 0.2 + 0.005)
        10       || BigDecimal.valueOf(90.5)     // 100 × (1 − 0.1 + 0.005)
    }

    @Unroll
    def "liquidation price for #leverage× short ≈ entry × (1 + 1/leverage − mm%)"() {
        when:
        def liq = manager.calculateLiquidationPrice(
                BigDecimal.valueOf(100), OrderSide.SELL, leverage, new BigDecimal("0.5"))

        then:
        (liq - expected).abs() < new BigDecimal("0.1")

        where:
        leverage || expected
        3        || BigDecimal.valueOf(132.833)
        5        || BigDecimal.valueOf(119.5)
        10       || BigDecimal.valueOf(109.5)
    }

    def "liquidation price for spot returns entry price unchanged"() {
        when:
        def liq = manager.calculateLiquidationPrice(
                BigDecimal.valueOf(100), OrderSide.BUY, 1, new BigDecimal("0.5"))

        then:
        liq == BigDecimal.valueOf(100)
    }

    def "LIQUIDATED takes priority over TP/SL when leveraged long crosses liquidation"() {
        given: "10x long at 100, liq ≈ 90.5, price drops to 85"
        def position = leveragedLong(BigDecimal.valueOf(100), new BigDecimal("90.50"))
        position.setTakeProfit(BigDecimal.valueOf(112))
        position.setStopLoss(BigDecimal.valueOf(96))         // SL also breached

        when:
        def result = manager.checkExitCondition(position, BigDecimal.valueOf(85))

        then:
        result.isPresent()
        result.get() == TakeProfitStopLossManager.EXIT_LIQUIDATED
    }

    def "leveraged short triggers LIQUIDATED when price rises past liquidation"() {
        given: "5x short at 100, liq ≈ 119.5, price jumps to 125"
        def position = leveragedShort(BigDecimal.valueOf(100), new BigDecimal("119.50"))
        position.setTakeProfit(BigDecimal.valueOf(88))
        position.setStopLoss(BigDecimal.valueOf(104))

        when:
        def result = manager.checkExitCondition(position, BigDecimal.valueOf(125))

        then:
        result.isPresent()
        result.get() == TakeProfitStopLossManager.EXIT_LIQUIDATED
    }

    def "leveraged position within safe range returns empty"() {
        given:
        def position = leveragedLong(BigDecimal.valueOf(100), new BigDecimal("90.50"))
        position.setTakeProfit(BigDecimal.valueOf(112))
        position.setStopLoss(BigDecimal.valueOf(96))

        when:
        def result = manager.checkExitCondition(position, BigDecimal.valueOf(105))

        then:
        !result.isPresent()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static Position leveragedLong(BigDecimal entry, BigDecimal liq) {
        Position.builder()
                .side(OrderSide.BUY)
                .entryPrice(entry)
                .quantity(BigDecimal.valueOf(0.1))
                .takeProfit(entry.multiply(BigDecimal.valueOf(1.12)))
                .stopLoss(entry.multiply(BigDecimal.valueOf(0.96)))
                .leverage((short) 10)
                .liquidationPrice(liq)
                .build()
    }

    private static Position leveragedShort(BigDecimal entry, BigDecimal liq) {
        Position.builder()
                .side(OrderSide.SELL)
                .entryPrice(entry)
                .quantity(BigDecimal.valueOf(0.1))
                .takeProfit(entry.multiply(BigDecimal.valueOf(0.88)))
                .stopLoss(entry.multiply(BigDecimal.valueOf(1.04)))
                .leverage((short) 5)
                .liquidationPrice(liq)
                .build()
    }

    private static TradingConfig buildConfig(BigDecimal tpPct, BigDecimal slPct) {
        def risk = new TradingConfig.Risk()
        risk.takeProfitPct = tpPct
        risk.stopLossPct   = slPct
        risk.maxConcurrentPositions = 3
        risk.maxDailyLossPct = BigDecimal.valueOf(5)
        risk.maxConsecutiveLosses = 5
        risk.maxPositionPct = BigDecimal.valueOf(2)

        def cfg = new TradingConfig()
        cfg.pairs  = ["BTC-EUR"]
        cfg.mode   = "PAPER"
        cfg.paperBalance = BigDecimal.valueOf(10_000)
        cfg.pollingIntervalSeconds = 30
        cfg.risk   = risk
        cfg.strategy = new TradingConfig.Strategy()
        return cfg
    }

    private static Position buyPosition(BigDecimal entry, BigDecimal tp, BigDecimal sl) {
        Position.builder()
                .side(OrderSide.BUY)
                .entryPrice(entry)
                .takeProfit(tp)
                .stopLoss(sl)
                .quantity(BigDecimal.valueOf(0.001))
                .build()
    }

    private static Position sellPosition(BigDecimal entry, BigDecimal tp, BigDecimal sl) {
        Position.builder()
                .side(OrderSide.SELL)
                .entryPrice(entry)
                .takeProfit(tp)
                .stopLoss(sl)
                .quantity(BigDecimal.valueOf(0.001))
                .build()
    }
}
