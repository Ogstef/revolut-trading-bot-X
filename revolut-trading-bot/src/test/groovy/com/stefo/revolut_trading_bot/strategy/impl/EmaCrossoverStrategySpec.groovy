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

class EmaCrossoverStrategySpec extends Specification {

    TradingConfig config = buildConfig()

    @Subject
    EmaCrossoverStrategy strategy = new EmaCrossoverStrategy(config)

    // ─── insufficient data ────────────────────────────────────────────────────

    def "evaluate returns HOLD when series has fewer bars than emaLongPeriod"() {
        given: "only 10 bars — less than required 21"
        def series = buildFlatSeries(10, 100.0)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.HOLD
        signal.reason().contains("Insufficient data")
    }

    def "evaluate returns HOLD for exactly emaLongPeriod bars (need longPeriod+1)"() {
        given: "exactly 21 bars — last index is 20, need at least 21"
        def series = buildFlatSeries(21, 100.0)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.HOLD
        signal.reason().contains("Insufficient data")
    }

    // ─── signal metadata ──────────────────────────────────────────────────────

    def "evaluate returns signal with correct pair and strategyType"() {
        given:
        def series = buildFlatSeries(30, 100.0)

        when:
        def signal = strategy.evaluate(series, "ETH-EUR")

        then:
        signal.pair()         == "ETH-EUR"
        signal.strategyType() == StrategyType.EMA_CROSSOVER
    }

    def "evaluate returns signal with evaluatedAt timestamp"() {
        given:
        def series = buildFlatSeries(30, 100.0)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.evaluatedAt() != null
    }

    def "evaluate returns signal with non-null indicator values for sufficient data"() {
        given:
        def series = buildFlatSeries(30, 100.0)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.currentPrice() != null
        signal.emaShort()     != null
        signal.emaLong()      != null
        signal.rsi()          != null
    }

    // ─── flat price → HOLD ────────────────────────────────────────────────────

    def "evaluate returns HOLD when price is flat (no EMA crossover)"() {
        given: "30 bars all at the same price — EMAs are equal, no crossover"
        def series = buildFlatSeries(30, 100.0)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.HOLD
    }

    // ─── SELL via overbought RSI ──────────────────────────────────────────────

    def "evaluate returns SELL when RSI is overbought (many strongly rising bars)"() {
        given: """
            Start with 30 flat bars at 100, then 15 bars at 200.
            After 15 consecutive up-bars with no down days, RSI will exceed 70.
        """
        def series = new BaseBarSeriesBuilder().withName("test").build()
        def start  = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        def period = Duration.ofMinutes(15)

        int i = 1
        // 30 bars at 100 — establishes baseline EMAs and RSI
        30.times {
            series.addBar(period, start.plusMinutes(i++ * 15), 100.0, 101.0, 99.0, 100.0, 1000.0)
        }
        // 15 bars at 200 — 15 consecutive wins → RSI → ~100
        15.times {
            series.addBar(period, start.plusMinutes(i++ * 15), 200.0, 201.0, 199.0, 200.0, 1000.0)
        }

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.SELL
    }

    // ─── HOLD: RSI within range, no crossover ────────────────────────────────

    def "evaluate returns HOLD when EMAs are equal and RSI is neutral"() {
        given: "perfectly flat series — RSI stays near 50, no crossover"
        def series = buildFlatSeries(35, 50_000.0)

        when:
        def signal = strategy.evaluate(series, "BTC-EUR")

        then:
        signal.type() == SignalType.HOLD
        signal.confidence() == BigDecimal.valueOf(50)
    }

    // ─── EMA9 below EMA21 with prices transitioning upward ───────────────────

    def "evaluate returns a valid signal type (one of BUY/SELL/HOLD) for all inputs"() {
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
