package com.stefo.revolut_trading_bot.backtest

import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import com.stefo.revolut_trading_bot.repository.BacktestRunRepository
import com.stefo.revolut_trading_bot.repository.CandlestickRepository
import com.stefo.revolut_trading_bot.strategy.TradingStrategy
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal
import java.time.LocalDateTime

class BacktestServiceSpec extends Specification {

    CandlestickRepository candleRepo = Mock()
    BacktestRunRepository runRepo = Mock()
    TradingConfig config = buildConfig()
    List<TradingStrategy> strategies = []

    @Subject
    BacktestService service = new BacktestService(candleRepo, runRepo, config, strategies)

    def "rejects unknown pair with IllegalArgumentException"() {
        when:
        service.run(req("XRP-EUR", StrategyType.RSI_MOMENTUM, "15m"))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Unknown pair")
    }

    def "rejects unknown interval"() {
        when:
        service.run(req("BTC-EUR", StrategyType.RSI_MOMENTUM, "5m"))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Unknown interval")
    }

    def "rejects end_date <= start_date"() {
        when:
        def r = new BacktestRequest("BTC-EUR", StrategyType.RSI_MOMENTUM, "15m",
                LocalDateTime.now(), LocalDateTime.now().minusDays(1),
                null, null, null, null)
        service.run(r)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("end_date must be after")
    }

    def "rejects insufficient candle data"() {
        given:
        candleRepo.findByPairAndIntervalOrderByTimestampAsc("BTC-EUR", "15m") >> []

        when:
        service.run(req("BTC-EUR", StrategyType.RSI_MOMENTUM, "15m"))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Insufficient candles")
    }

    def "walk-forward rejects window count out of [2, 10]"() {
        when:
        service.runWalkForward(req("BTC-EUR", StrategyType.RSI_MOMENTUM, "15m"), windows)

        then:
        thrown(IllegalArgumentException)

        where:
        windows << [0, 1, 11, 100]
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static BacktestRequest req(String pair, StrategyType strategy, String interval) {
        return new BacktestRequest(pair, strategy, interval,
                LocalDateTime.now().minusDays(30), LocalDateTime.now(),
                null, null, null, null)
    }

    private static TradingConfig buildConfig() {
        def cfg = new TradingConfig()
        cfg.pairs = ["BTC-EUR", "ETH-EUR"]
        cfg.intervals = [15, 60]
        cfg.mode = "PAPER"
        cfg.paperBalance = BigDecimal.valueOf(10_000)
        cfg.pollingIntervalSeconds = 30

        def s = new TradingConfig.Strategy()
        s.emaShortPeriod = 9
        s.emaLongPeriod = 21
        s.rsiPeriod = 14
        s.rsiOverbought = 70
        s.rsiOversold = 30
        cfg.strategy = s

        def r = new TradingConfig.Risk()
        r.maxPositionPct = BigDecimal.valueOf(2)
        r.maxConcurrentPositions = 3
        r.maxDailyLossPct = BigDecimal.valueOf(10)
        r.maxConsecutiveLosses = 5
        r.takeProfitPct = BigDecimal.valueOf(5)
        r.stopLossPct = BigDecimal.valueOf(3)
        cfg.risk = r

        def c = new TradingConfig.Costs()
        c.feeRate = new BigDecimal("0.0009")
        c.slippageRate = new BigDecimal("0.0005")
        cfg.costs = c

        return cfg
    }
}
