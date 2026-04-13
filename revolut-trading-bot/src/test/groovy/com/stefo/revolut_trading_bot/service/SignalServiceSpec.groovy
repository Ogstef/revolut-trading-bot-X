package com.stefo.revolut_trading_bot.service

import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.model.entity.SignalLog
import com.stefo.revolut_trading_bot.model.enums.SignalType
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import com.stefo.revolut_trading_bot.repository.SignalLogRepository
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal
import java.time.LocalDateTime

class SignalServiceSpec extends Specification {

    SignalLogRepository repository = Mock()
    TradingConfig       config     = buildConfig()

    @Subject
    SignalService service = new SignalService(repository, config)

    // ─── getSummary ───────────────────────────────────────────────────────────

    def "getSummary uses primary pair when none provided"() {
        when:
        service.getSummary(null)

        then:
        1 * repository.countSignalsByStrategy("BTC-EUR") >> []
    }

    def "getSummary uses the provided pair"() {
        when:
        service.getSummary("ETH-EUR")

        then:
        1 * repository.countSignalsByStrategy("ETH-EUR") >> []
        0 * repository.countSignalsByStrategy("BTC-EUR")
    }

    def "getSummary maps repository rows to strategy/signalType/count maps"() {
        given:
        repository.countSignalsByStrategy("BTC-EUR") >> [
            [StrategyType.EMA_CROSSOVER, SignalType.BUY, 10L] as Object[],
            [StrategyType.EMA_CROSSOVER, SignalType.SELL, 5L] as Object[],
            [StrategyType.MACD, SignalType.HOLD, 20L] as Object[]
        ]

        when:
        def result = service.getSummary("BTC-EUR")

        then:
        result.size() == 3
        result[0].strategy   == StrategyType.EMA_CROSSOVER
        result[0].signalType == SignalType.BUY
        result[0].count      == 10L
        result[2].strategy   == StrategyType.MACD
    }

    def "getSummary returns empty list when no signals in DB"() {
        given:
        repository.countSignalsByStrategy("BTC-EUR") >> []

        when:
        def result = service.getSummary("BTC-EUR")

        then:
        result.isEmpty()
    }

    // ─── getSignalStrategies ──────────────────────────────────────────────────

    def "getSignalStrategies uses primary pair when none provided"() {
        given:
        repository.findRecentByPairAndStrategy("BTC-EUR", StrategyType.EMA_CROSSOVER, 20) >> []

        when:
        service.getSignalStrategies(null, StrategyType.EMA_CROSSOVER, 20)

        then:
        1 * repository.findRecentByPairAndStrategy("BTC-EUR", StrategyType.EMA_CROSSOVER, 20)
    }

    def "getSignalStrategies passes provided pair to repository"() {
        given:
        repository.findRecentByPairAndStrategy("SOL-EUR", StrategyType.MACD, 10) >> []

        when:
        service.getSignalStrategies("SOL-EUR", StrategyType.MACD, 10)

        then:
        1 * repository.findRecentByPairAndStrategy("SOL-EUR", StrategyType.MACD, 10)
    }

    def "getSignalStrategies returns the list from the repository"() {
        given:
        def logs = [signalLog("BTC-EUR", SignalType.BUY), signalLog("BTC-EUR", SignalType.SELL)]
        repository.findRecentByPairAndStrategy("BTC-EUR", StrategyType.EMA_CROSSOVER, 5) >> logs

        when:
        def result = service.getSignalStrategies("BTC-EUR", StrategyType.EMA_CROSSOVER, 5)

        then:
        result == logs
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

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

    private static SignalLog signalLog(String pair, SignalType type) {
        def log = new SignalLog()
        log.pair        = pair
        log.signalType  = type
        log.strategyName = StrategyType.EMA_CROSSOVER
        log.confidence  = BigDecimal.valueOf(75)
        log.createdAt   = LocalDateTime.now()
        log
    }
}
