package com.stefo.revolut_trading_bot.service

import com.stefo.revolut_trading_bot.model.entity.DisabledTriple
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import com.stefo.revolut_trading_bot.repository.DisabledTripleRepository
import spock.lang.Specification
import spock.lang.Subject

import java.time.LocalDateTime

class TripleConfigServiceSpec extends Specification {

    DisabledTripleRepository repository = Mock()
    BotEventService botEventService = Mock()

    @Subject
    TripleConfigService service = new TripleConfigService(repository, botEventService)

    def "load() warms cache from repository"() {
        given:
        def row = DisabledTriple.builder()
                .pair("BTC-EUR")
                .strategyName(StrategyType.TRIPLE_EMA)
                .interval("15m")
                .disabledAt(LocalDateTime.now())
                .reason("loaded from DB")
                .build()
        repository.findAll() >> [row]

        when:
        service.load()

        then:
        !service.isEnabled("BTC-EUR", "15m", StrategyType.TRIPLE_EMA)
        service.isEnabled("BTC-EUR", "15m", StrategyType.MACD)         // different strategy
        service.isEnabled("ETH-EUR", "15m", StrategyType.TRIPLE_EMA)   // different pair
        service.isEnabled("BTC-EUR", "1h",  StrategyType.TRIPLE_EMA)   // different interval
    }

    def "isEnabled returns true when cache is empty"() {
        given:
        repository.findAll() >> []
        service.load()

        expect:
        service.isEnabled("BTC-EUR", "15m", StrategyType.RSI_MOMENTUM)
        service.isEnabled("ETH-EUR", "1d",  StrategyType.BOLLINGER)
    }

    def "disable adds to cache, persists, and audits"() {
        given:
        repository.findAll() >> []
        service.load()

        when:
        service.disable("BTC-EUR", "15m", StrategyType.TRIPLE_EMA, "data-driven cut")

        then:
        1 * repository.save({ DisabledTriple it ->
            it.pair == "BTC-EUR" &&
            it.strategyName == StrategyType.TRIPLE_EMA &&
            it.interval == "15m" &&
            it.reason == "data-driven cut" &&
            it.disabledAt != null
        }) >> { DisabledTriple it -> it }
        1 * botEventService.recordTripleToggled("BTC-EUR", "15m", StrategyType.TRIPLE_EMA, false, "data-driven cut")
        and:
        !service.isEnabled("BTC-EUR", "15m", StrategyType.TRIPLE_EMA)
    }

    def "enable removes from cache, deletes, and audits"() {
        given: "a triple already disabled in cache"
        def row = DisabledTriple.builder()
                .pair("BTC-EUR")
                .strategyName(StrategyType.TRIPLE_EMA)
                .interval("15m")
                .disabledAt(LocalDateTime.now())
                .build()
        repository.findAll() >> [row]
        service.load()
        assert !service.isEnabled("BTC-EUR", "15m", StrategyType.TRIPLE_EMA)

        when:
        service.enable("BTC-EUR", "15m", StrategyType.TRIPLE_EMA)

        then:
        1 * repository.deleteByPairAndStrategyNameAndInterval("BTC-EUR", StrategyType.TRIPLE_EMA, "15m")
        1 * botEventService.recordTripleToggled("BTC-EUR", "15m", StrategyType.TRIPLE_EMA, true, null)
        and:
        service.isEnabled("BTC-EUR", "15m", StrategyType.TRIPLE_EMA)
    }

    def "listDisabled delegates to the repository ordering by disabledAt desc"() {
        given:
        def rows = [DisabledTriple.builder().pair("X-Y").strategyName(StrategyType.MACD).interval("1h").build()]
        repository.findAllByOrderByDisabledAtDesc() >> rows

        expect:
        service.listDisabled() == rows
    }

    def "disable then enable round-trips back to enabled"() {
        given:
        repository.findAll() >> []
        service.load()
        repository.save(_) >> { DisabledTriple it -> it }

        when:
        service.disable("ETH-EUR", "4h", StrategyType.SUPERTREND, null)
        then:
        !service.isEnabled("ETH-EUR", "4h", StrategyType.SUPERTREND)

        when:
        service.enable("ETH-EUR", "4h", StrategyType.SUPERTREND)
        then:
        service.isEnabled("ETH-EUR", "4h", StrategyType.SUPERTREND)
    }
}
