package com.stefo.revolut_trading_bot.strategy.impl

import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.model.enums.SignalType
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import org.ta4j.core.BarSeries
import org.ta4j.core.BaseBarSeriesBuilder
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime

class MacdStrategySpec extends Specification {

    TradingConfig config = buildConfig()

    @Subject
    MacdStrategy strategy = new MacdStrategy(config)

    // ─── insufficient data ────────────────────────────────────────────────────

    def "returns HOLD when series has fewer than 36 bars"() {
        given: "only 20 bars — MACD needs SLOW(26) + SIGNAL(9) + 1 = 36 bars minimum"
        def series = buildFlatSeries(20, 100.0)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.HOLD
        signal.reason().contains("insufficient data")
    }

    // ─── signal metadata ──────────────────────────────────────────────────────

    def "returns MACD strategyType and non-null evaluatedAt"() {
        given:
        def series = buildFlatSeries(50, 100.0)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.strategyType() == StrategyType.MACD
        signal.evaluatedAt()  != null
    }

    // ─── flat price → HOLD ────────────────────────────────────────────────────

    def "returns HOLD for flat price series (no crossover)"() {
        given: "50 flat bars — EMA12 and EMA26 converge to the same value, histogram stays near zero with no cross"
        def series = buildFlatSeries(50, 100.0)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.HOLD
    }

    // ─── zero-line filter guard — BUY blocked when MACD line already positive ─

    def "returns HOLD when histogram crosses above zero but macd line is already positive"() {
        given: """
            30 flat bars at 100 establish a baseline.
            20 bars of strong uptrend at 150 push EMA12 far above EMA26 — the MACD line is strongly
            positive. Any histogram zero-crosses that happen here are mid-cycle blips and must be
            filtered out by the new 'macdNow < 0' guard.
        """
        def series = new BaseBarSeriesBuilder().withName("test-buy-guard").build()
        def start  = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        def period = Duration.ofMinutes(15)
        int i = 1

        // 30 flat bars at 100 — establishes baseline MACD near zero
        30.times {
            series.addBar(period, start.plusMinutes(i++ * 15), 100.0, 101.0, 99.0, 100.0, 1000.0)
        }
        // 20 bars at 150 — EMA12 races ahead of EMA26, MACD line becomes strongly positive
        20.times {
            series.addBar(period, start.plusMinutes(i++ * 15), 150.0, 151.0, 149.0, 150.0, 1000.0)
        }

        when: "evaluate on the final bar of the strong uptrend"
        def signal = strategy.evaluate(series, "BTC-EUR")

        then: "MACD line is positive — any histogram cross-up is a mid-cycle blip → HOLD"
        signal.type() == SignalType.HOLD
    }

    // ─── zero-line filter guard — SELL blocked when MACD line already negative ─

    def "returns HOLD when histogram crosses below zero but macd line is already negative"() {
        given: """
            30 flat bars at 100 establish a baseline.
            20 bars of strong downtrend at 50 push EMA12 far below EMA26 — the MACD line is strongly
            negative. Any histogram zero-crosses that happen here are mid-cycle blips and must be
            filtered out by the new 'macdNow > 0' guard.
        """
        def series = new BaseBarSeriesBuilder().withName("test-sell-guard").build()
        def start  = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        def period = Duration.ofMinutes(15)
        int i = 1

        // 30 flat bars at 100 — establishes baseline MACD near zero
        30.times {
            series.addBar(period, start.plusMinutes(i++ * 15), 100.0, 101.0, 99.0, 100.0, 1000.0)
        }
        // 20 bars at 50 — EMA12 drops hard below EMA26, MACD line becomes strongly negative
        20.times {
            series.addBar(period, start.plusMinutes(i++ * 15), 50.0, 51.0, 49.0, 50.0, 1000.0)
        }

        when: "evaluate on the final bar of the strong downtrend"
        def signal = strategy.evaluate(series, "BTC-EUR")

        then: "MACD line is negative — any histogram cross-down is a mid-cycle blip → HOLD"
        signal.type() == SignalType.HOLD
    }

    // ─── valid signal type for any input ─────────────────────────────────────

    def "returns valid signal type for any input"() {
        given:
        def series = buildTrendingSeries()

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then: "signal type must be one of the valid enum values"
        signal.type() in [SignalType.BUY, SignalType.SELL, SignalType.HOLD]
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Builds a BarSeries with N bars all at the same close price. */
    private static BarSeries buildFlatSeries(int bars, double price) {
        def series = new BaseBarSeriesBuilder().withName("flat-test").build()
        def start  = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        def period = Duration.ofMinutes(15)
        (1..bars).each { i ->
            series.addBar(period, start.plusMinutes(i * 15),
                    price, price + 1, price - 1, price, 1000.0)
        }
        series
    }

    /**
     * Builds a series with a recognisable trend pattern:
     * - 30 bars at 100 (stable baseline)
     * - 10 bars slowly rising to 110
     * - 5 bars dipping to 95
     */
    private static BarSeries buildTrendingSeries() {
        def series = new BaseBarSeriesBuilder().withName("trend-test").build()
        def start  = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        def period = Duration.ofMinutes(15)
        int i = 1

        30.times {
            series.addBar(period, start.plusMinutes(i++ * 15), 100.0, 101.0, 99.0, 100.0, 1000.0)
        }
        10.times { j ->
            double p = 100.0 + j
            series.addBar(period, start.plusMinutes(i++ * 15), p, p + 1, p - 1, p, 1000.0)
        }
        5.times { j ->
            double p = 110.0 - j * 3
            series.addBar(period, start.plusMinutes(i++ * 15), p, p + 1, p - 1, p, 1000.0)
        }
        series
    }

    private static TradingConfig buildConfig() {
        def strategy = new TradingConfig.Strategy()
        strategy.emaShortPeriod = 9
        strategy.emaLongPeriod  = 21
        strategy.rsiPeriod      = 14
        strategy.rsiOverbought  = 70
        strategy.rsiOversold    = 30

        def risk = new TradingConfig.Risk()
        risk.maxConcurrentPositions = 3
        risk.maxDailyLossPct = BigDecimal.valueOf(5)
        risk.maxConsecutiveLosses = 5
        risk.maxPositionPct = BigDecimal.valueOf(2)
        risk.takeProfitPct  = BigDecimal.valueOf(5)
        risk.stopLossPct    = BigDecimal.valueOf(3)

        def cfg = new TradingConfig()
        cfg.pairs = ["BTC-EUR"]
        cfg.mode  = "PAPER"
        cfg.paperBalance = BigDecimal.valueOf(10_000)
        cfg.pollingIntervalSeconds = 30
        cfg.strategy = strategy
        cfg.risk     = risk
        cfg
    }
}
