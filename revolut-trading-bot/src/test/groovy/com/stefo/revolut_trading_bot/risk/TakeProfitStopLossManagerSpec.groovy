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

    // ─── Helpers ──────────────────────────────────────────────────────────────

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
