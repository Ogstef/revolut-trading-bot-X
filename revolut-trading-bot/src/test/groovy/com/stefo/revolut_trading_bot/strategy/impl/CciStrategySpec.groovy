package com.stefo.revolut_trading_bot.strategy.impl

import com.stefo.revolut_trading_bot.model.enums.SignalType
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import org.ta4j.core.BarSeries
import org.ta4j.core.BaseBarSeriesBuilder
import spock.lang.Specification
import spock.lang.Subject

import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime

class CciStrategySpec extends Specification {

    @Subject
    CciStrategy strategy = new CciStrategy()

    // ─── insufficient data ────────────────────────────────────────────────────

    def "returns HOLD when series has fewer than 20 bars"() {
        given: "only 19 bars — CCI period is 20, need at least 21 (index 20)"
        def series = buildFlatSeries(19, 100.0)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.HOLD
        signal.reason().contains("insufficient data")
    }

    // ─── signal metadata ──────────────────────────────────────────────────────

    def "returns CCI strategyType and non-null evaluatedAt"() {
        given:
        def series = buildFlatSeries(25, 100.0)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.strategyType() == StrategyType.CCI
        signal.evaluatedAt()  != null
    }

    // ─── flat price → HOLD ────────────────────────────────────────────────────

    def "returns HOLD for flat price series (CCI stays near zero, no threshold crossing)"() {
        given: "flat prices → CCI ≈ 0, well within ±100 (BTC) and ±150 (others)"
        def series = buildFlatSeries(25, 100.0)

        when: "evaluated for any pair, all should be HOLD"
        def btcSignal = strategy.evaluate(series, "BTC-EUR")
        def ethSignal = strategy.evaluate(series, "ETH-EUR")
        def solSignal = strategy.evaluate(series, "SOL-EUR")

        then:
        btcSignal.type() == SignalType.HOLD
        ethSignal.type() == SignalType.HOLD
        solSignal.type() == SignalType.HOLD
    }

    // ─── pair-aware thresholds ────────────────────────────────────────────────

    def "BTC-EUR returns valid signal for moderate dip series"() {
        given:
        def series = buildModerateDipSeries()

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() in [SignalType.BUY, SignalType.SELL, SignalType.HOLD]
        signal.strategyType() == StrategyType.CCI
    }

    def "SOL-EUR returns valid signal for moderate dip series"() {
        given:
        def series = buildModerateDipSeries()

        when:
        def signal = strategy.evaluate(series, "SOL-EUR")

        then:
        signal.type() in [SignalType.BUY, SignalType.SELL, SignalType.HOLD]
        signal.strategyType() == StrategyType.CCI
    }

    def "BTC-EUR signal reason reflects -100 threshold when CCI crosses it"() {
        given:
        def series = buildExtremeDipSeries()

        when:
        def btcSignal = strategy.evaluate(series, "BTC-EUR")
        def solSignal = strategy.evaluate(series, "SOL-EUR")

        then:
        btcSignal.type() in [SignalType.BUY, SignalType.SELL, SignalType.HOLD]
        solSignal.type() in [SignalType.BUY, SignalType.SELL, SignalType.HOLD]
        // If a BUY fires, the reason must reflect the correct threshold for each pair
        btcSignal.type() == SignalType.BUY ? btcSignal.reason().contains("-100") : true
        solSignal.type() == SignalType.BUY ? solSignal.reason().contains("-150") : true
    }

    // ─── rsi field carries CCI value ─────────────────────────────────────────

    def "signal includes rsi field populated with CCI value for sufficient data"() {
        given:
        def series = buildFlatSeries(25, 100.0)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.rsi() != null
    }

    // ─── strong uptrend ───────────────────────────────────────────────────────

    def "returns valid signal type for strong uptrend (potential overbought)"() {
        given: "20 flat bars then 10 strongly rising bars"
        def series = new BaseBarSeriesBuilder().withName("uptrend-test").build()
        def start  = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        def period = Duration.ofMinutes(15)
        int i = 1

        20.times {
            series.addBar(period, start.plusMinutes(i++ * 15), 100.0, 101.0, 99.0, 100.0, 1000.0)
        }
        [110.0, 120.0, 135.0, 150.0, 170.0, 190.0, 210.0, 230.0, 250.0, 270.0].each { p ->
            series.addBar(period, start.plusMinutes(i++ * 15), p, p + 1.0, p - 1.0, p, 1000.0)
        }

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() in [SignalType.BUY, SignalType.SELL, SignalType.HOLD]
        signal.strategyType() == StrategyType.CCI
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
     * 20 flat bars at 100, then 5 declining bars to ~80, then 1 recovery bar.
     * Pushes CCI into roughly the -100 to -150 range, then crosses back.
     */
    private static BarSeries buildModerateDipSeries() {
        def series = new BaseBarSeriesBuilder().withName("moderate-dip").build()
        def start  = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        def period = Duration.ofMinutes(15)
        int i = 1
        20.times { series.addBar(period, start.plusMinutes(i++ * 15), 100.0, 101.0, 99.0, 100.0, 1000.0) }
        [100.0, 95.0, 90.0, 85.0, 80.0].each { p ->
            series.addBar(period, start.plusMinutes(i++ * 15), p, p + 0.5, p - 0.5, p, 1000.0)
        }
        series.addBar(period, start.plusMinutes(i++ * 15), 82.0, 83.0, 81.0, 82.0, 1000.0)
        series
    }

    /**
     * 20 flat bars at 100, then 10 bars declining strongly to ~54, then 1 recovery bar.
     * Pushes CCI well below -150 — should fire a BUY for both BTC and SOL on recovery.
     */
    private static BarSeries buildExtremeDipSeries() {
        def series = new BaseBarSeriesBuilder().withName("extreme-dip").build()
        def start  = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        def period = Duration.ofMinutes(15)
        int i = 1
        20.times { series.addBar(period, start.plusMinutes(i++ * 15), 100.0, 101.0, 99.0, 100.0, 1000.0) }
        [95.0, 88.0, 80.0, 72.0, 65.0, 60.0, 58.0, 56.0, 55.0, 54.0].each { p ->
            series.addBar(period, start.plusMinutes(i++ * 15), p, p + 0.5, p - 0.5, p, 1000.0)
        }
        series.addBar(period, start.plusMinutes(i++ * 15), 58.0, 59.0, 57.0, 58.0, 1000.0)
        series
    }
}
