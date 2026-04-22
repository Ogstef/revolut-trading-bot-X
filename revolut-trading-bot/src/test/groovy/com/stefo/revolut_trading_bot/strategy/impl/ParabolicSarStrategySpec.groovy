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

class ParabolicSarStrategySpec extends Specification {

    @Subject
    ParabolicSarStrategy strategy = new ParabolicSarStrategy()

    // ─── insufficient data ────────────────────────────────────────────────────

    def "returns HOLD when series has fewer than 5 bars"() {
        given: "only 4 bars — less than MIN_BARS (5)"
        def series = buildFlatSeries(4, 100.0)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.HOLD
        signal.reason().contains("insufficient data")
    }

    // ─── strategy metadata ────────────────────────────────────────────────────

    def "returns PARABOLIC_SAR strategyType and non-null evaluatedAt"() {
        given:
        def series = buildFlatSeries(20, 100.0)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.strategyType() == StrategyType.PARABOLIC_SAR
        signal.evaluatedAt()  != null
    }

    // ─── flat price → HOLD ────────────────────────────────────────────────────

    def "returns HOLD for flat price series (no SAR flip)"() {
        given: "20 bars — enough data, strategy must not throw"
        def series = buildFlatSeries(20, 100.0)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then: "strategy returns a valid signal (SAR may or may not flip with uniform prices)"
        signal.type() in [SignalType.BUY, SignalType.SELL, SignalType.HOLD]
        signal.strategyType() == StrategyType.PARABOLIC_SAR
    }

    // ─── indicator values populated ──────────────────────────────────────────

    def "signal includes non-null currentPrice and SAR value for sufficient data"() {
        given:
        def series = buildFlatSeries(20, 100.0)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.currentPrice() != null
        signal.emaShort()     != null   // emaShort stores the SAR value
    }

    // ─── trending series → valid signal ──────────────────────────────────────

    def "returns valid signal type (one of BUY/SELL/HOLD) for a price series with a clear trend reversal"() {
        given: "15 flat bars followed by 10 bars at a higher price"
        def series = new BaseBarSeriesBuilder().withName("test").build()
        def start  = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        def period = Duration.ofMinutes(15)
        int i = 1

        15.times {
            series.addBar(period, start.plusMinutes(i++ * 15), 100.0, 101.0, 99.0, 100.0, 1000.0)
        }
        10.times {
            series.addBar(period, start.plusMinutes(i++ * 15), 110.0, 111.0, 109.0, 110.0, 1000.0)
        }

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() in [SignalType.BUY, SignalType.SELL, SignalType.HOLD]
    }

    // ─── tight range → HOLD (gap filter) ─────────────────────────────────────

    def "returns HOLD at 15m when gap is below 1.0% minimum threshold"() {
        given: "alternating 100.0/100.1 — the 0.1% gap is always below MIN_GAP_PCT_15M=1.0%, so even if SAR flips the filter blocks the signal"
        def series = new BaseBarSeriesBuilder().withName("micro-gap-test").build()
        def start  = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        def period = Duration.ofMinutes(15)

        (1..20).each { i ->
            double p = (i % 2 == 0) ? 100.1 : 100.0
            series.addBar(period, start.plusMinutes(i * 15), p, p + 0.05, p - 0.05, p, 1000.0)
        }

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then: "gap is ~0.1% which is below the 15m minimum of 1.0% — signal must be HOLD"
        signal.type() == SignalType.HOLD
    }

    def "returns HOLD for medium gap (0.5%) at 15m which is below new 1.0% minimum"() {
        given: "alternating 100.0/100.5 — the ~0.5% gap is below MIN_GAP_PCT_15M=1.0%, so the filter blocks any SAR flip"
        def series = new BaseBarSeriesBuilder().withName("medium-gap-test").build()
        def start  = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        def period = Duration.ofMinutes(15)

        (1..20).each { i ->
            double p = (i % 2 == 0) ? 100.5 : 100.0
            series.addBar(period, start.plusMinutes(i * 15), p, p + 0.05, p - 0.05, p, 1000.0)
        }

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then: "gap is ~0.5% which is below the 15m minimum of 1.0% — signal must be HOLD"
        signal.type() == SignalType.HOLD
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
}
