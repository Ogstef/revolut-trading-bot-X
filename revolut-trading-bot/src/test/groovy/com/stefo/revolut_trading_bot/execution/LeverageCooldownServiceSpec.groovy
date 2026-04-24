package com.stefo.revolut_trading_bot.execution

import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.model.entity.Position
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import com.stefo.revolut_trading_bot.model.enums.TradingVehicle
import com.stefo.revolut_trading_bot.repository.TradeRepository
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal
import java.time.LocalDateTime

class LeverageCooldownServiceSpec extends Specification {

    TradeRepository tradeRepository = Mock()
    TradingConfig   config          = buildConfig(30)   // 30-minute cooldown

    @Subject
    LeverageCooldownService service = new LeverageCooldownService(config, tradeRepository)

    def "canOpen returns true when map is empty"() {
        expect:
        service.canOpen("BTC-EUR", "1h", StrategyType.EMA_CROSSOVER, TradingVehicle.LEV_3X, LocalDateTime.now())
    }

    def "canOpen returns false when recordClose happened less than cooldown ago"() {
        given:
        def position = leveragedPosition(TradingVehicle.LEV_3X)
        service.recordClose(position)

        when: "15 minutes after the close — still inside the 30-min window"
        def result = service.canOpen("BTC-EUR", "1h", StrategyType.EMA_CROSSOVER,
                TradingVehicle.LEV_3X, LocalDateTime.now().plusMinutes(15))

        then:
        !result
    }

    def "canOpen returns true when recordClose happened more than cooldown ago"() {
        given:
        def position = leveragedPosition(TradingVehicle.LEV_3X)
        service.recordClose(position)

        when: "35 minutes after the close — past the 30-min cooldown"
        def result = service.canOpen("BTC-EUR", "1h", StrategyType.EMA_CROSSOVER,
                TradingVehicle.LEV_3X, LocalDateTime.now().plusMinutes(35))

        then:
        result
    }

    def "canOpen always returns true when cooldown is disabled (0)"() {
        given:
        config.leverage.cooldownMinutes = 0
        def position = leveragedPosition(TradingVehicle.LEV_3X)
        service.recordClose(position)

        expect: "cooldown=0 means always allowed regardless of last close"
        service.canOpen("BTC-EUR", "1h", StrategyType.EMA_CROSSOVER,
                TradingVehicle.LEV_3X, LocalDateTime.now())
    }

    def "recordClose is a no-op for SPOT positions"() {
        given:
        def spot = leveragedPosition(TradingVehicle.SPOT)

        when:
        service.recordClose(spot)

        then: "canOpen on the same key still returns true because the map was not mutated"
        service.canOpen("BTC-EUR", "1h", StrategyType.EMA_CROSSOVER,
                TradingVehicle.SPOT, LocalDateTime.now())
    }

    def "cooldown is scoped per-quadruple — recording a close for LEV_3X does not block LEV_5X"() {
        given:
        service.recordClose(leveragedPosition(TradingVehicle.LEV_3X))

        expect: "LEV_5X on same (pair, interval, strategy) is unaffected"
        service.canOpen("BTC-EUR", "1h", StrategyType.EMA_CROSSOVER,
                TradingVehicle.LEV_5X, LocalDateTime.now())

        and: "LEV_3X itself is blocked"
        !service.canOpen("BTC-EUR", "1h", StrategyType.EMA_CROSSOVER,
                TradingVehicle.LEV_3X, LocalDateTime.now())
    }

    def "warmup preloads the cache from DB so restarts do not reset cooldowns"() {
        given: "DB has a leveraged close 10 minutes ago"
        def tenMinAgo = LocalDateTime.now().minusMinutes(10)
        tradeRepository.maxClosedAtPerLeveragedQuadruple() >> [
                (["BTC-EUR", "1h", StrategyType.EMA_CROSSOVER, TradingVehicle.LEV_3X, tenMinAgo] as Object[])
        ]

        when:
        service.warmup()

        then: "canOpen respects the preloaded timestamp — still inside 30-min window"
        !service.canOpen("BTC-EUR", "1h", StrategyType.EMA_CROSSOVER,
                TradingVehicle.LEV_3X, LocalDateTime.now())
    }

    def "warmup is a no-op when cooldown is disabled"() {
        given:
        config.leverage.cooldownMinutes = 0

        when:
        service.warmup()

        then: "DB is not queried"
        0 * tradeRepository.maxClosedAtPerLeveragedQuadruple()
    }

    def "warmup is a no-op when leverage is disabled"() {
        given:
        config.leverage.enabled = false

        when:
        service.warmup()

        then:
        0 * tradeRepository.maxClosedAtPerLeveragedQuadruple()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static Position leveragedPosition(TradingVehicle vehicle) {
        Position.builder()
                .pair("BTC-EUR")
                .interval("1h")
                .strategyName(StrategyType.EMA_CROSSOVER)
                .vehicle(vehicle)
                .leverage((short) vehicle.leverage)
                .build()
    }

    private static TradingConfig buildConfig(int cooldownMinutes) {
        def cfg = new TradingConfig()
        cfg.pairs = ["BTC-EUR"]
        cfg.mode  = "PAPER"
        cfg.paperBalance = BigDecimal.valueOf(10_000)
        cfg.pollingIntervalSeconds = 30
        cfg.strategy = new TradingConfig.Strategy()

        def risk = new TradingConfig.Risk()
        risk.takeProfitPct = BigDecimal.valueOf(12)
        risk.stopLossPct   = BigDecimal.valueOf(4)
        risk.maxConcurrentPositions = 3
        risk.maxDailyLossPct = BigDecimal.valueOf(10)
        risk.maxConsecutiveLosses = 15
        risk.maxPositionPct = BigDecimal.valueOf(2)
        cfg.risk = risk

        def lev = new TradingConfig.Leverage()
        lev.enabled = true
        lev.ratios  = [3, 5, 10]
        lev.pairs   = ["BTC-EUR"]
        lev.intervals = [60]
        lev.collateralPerStrategy = new BigDecimal("1000.00")
        lev.maintenanceMarginPct  = new BigDecimal("0.5")
        lev.fundingRatePer8h      = new BigDecimal("0.0001")
        lev.cooldownMinutes       = cooldownMinutes
        cfg.leverage = lev

        cfg
    }
}
