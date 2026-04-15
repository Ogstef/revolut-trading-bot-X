package com.stefo.revolut_trading_bot.risk

import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.model.entity.Trade
import com.stefo.revolut_trading_bot.model.enums.OrderStatus
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import com.stefo.revolut_trading_bot.repository.PositionRepository
import com.stefo.revolut_trading_bot.repository.TradeRepository
import com.stefo.revolut_trading_bot.service.BotEventService
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal
import java.time.LocalDateTime

class RiskManagerSpec extends Specification {

    PositionRepository positionRepository = Mock()
    TradeRepository    tradeRepository    = Mock()
    TradingConfig      config             = buildConfig()
    BotEventService    botEventService    = Mock()

    @Subject
    RiskManager riskManager = new RiskManager(positionRepository, tradeRepository, config, botEventService)

    // ─── validateForStrategy ─────────────────────────────────────────────────

    def "approved when all risk checks pass"() {
        given:
        positionRepository.countByStatusAndPairAndIntervalAndStrategyName(OrderStatus.OPEN, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> 0
        tradeRepository.sumPnlSinceAndPairAndIntervalAndStrategy(_ as LocalDateTime, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> BigDecimal.ZERO
        tradeRepository.findRecentTradesByPairAndIntervalAndStrategy("BTC-EUR", _, StrategyType.EMA_CROSSOVER, 5) >> []

        when:
        def result = riskManager.validateForStrategy(BigDecimal.valueOf(10_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then:
        result.approved()
        result.positionSizeEur() == new BigDecimal("200.00000000")
    }

    def "rejected when max concurrent positions reached"() {
        given: "already at the position cap (3)"
        positionRepository.countByStatusAndPairAndIntervalAndStrategyName(OrderStatus.OPEN, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> 3

        when:
        def result = riskManager.validateForStrategy(BigDecimal.valueOf(10_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then:
        !result.approved()
        result.reason().contains("Max concurrent positions")
    }

    def "rejected when daily loss breaches circuit breaker"() {
        given:
        positionRepository.countByStatusAndPairAndIntervalAndStrategyName(OrderStatus.OPEN, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> 0
        // Daily PnL is -600 EUR, threshold is -5% of 10_000 = -500 EUR
        tradeRepository.sumPnlSinceAndPairAndIntervalAndStrategy(_ as LocalDateTime, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> BigDecimal.valueOf(-600)

        when:
        def result = riskManager.validateForStrategy(BigDecimal.valueOf(10_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then:
        !result.approved()
        result.reason().contains("Daily loss circuit breaker")
    }

    def "rejected when consecutive loss circuit breaker trips"() {
        given:
        positionRepository.countByStatusAndPairAndIntervalAndStrategyName(OrderStatus.OPEN, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> 0
        tradeRepository.sumPnlSinceAndPairAndIntervalAndStrategy(_ as LocalDateTime, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> BigDecimal.ZERO
        tradeRepository.findRecentTradesByPairAndIntervalAndStrategy("BTC-EUR", _, StrategyType.EMA_CROSSOVER, 5) >> [
            losingTrade(), losingTrade(), losingTrade(), losingTrade(), losingTrade()
        ]

        when:
        def result = riskManager.validateForStrategy(BigDecimal.valueOf(10_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then:
        !result.approved()
        result.reason().contains("Consecutive loss circuit breaker")
    }

    def "consecutive loss count stops at first winning trade"() {
        given: "2 losses followed by a win and 2 more losses — count must be 2 not 4"
        positionRepository.countByStatusAndPairAndIntervalAndStrategyName(OrderStatus.OPEN, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> 0
        tradeRepository.sumPnlSinceAndPairAndIntervalAndStrategy(_ as LocalDateTime, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> BigDecimal.ZERO
        tradeRepository.findRecentTradesByPairAndIntervalAndStrategy("BTC-EUR", _, StrategyType.EMA_CROSSOVER, 5) >> [
            losingTrade(), losingTrade(), winningTrade(), losingTrade(), losingTrade()
        ]

        when:
        def result = riskManager.validateForStrategy(BigDecimal.valueOf(10_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then: "only 2 consecutive — below threshold of 5 → approved"
        result.approved()
    }

    def "position size is 2% of available balance"() {
        given:
        positionRepository.countByStatusAndPairAndIntervalAndStrategyName(OrderStatus.OPEN, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> 0
        tradeRepository.sumPnlSinceAndPairAndIntervalAndStrategy(_ as LocalDateTime, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> BigDecimal.ZERO
        tradeRepository.findRecentTradesByPairAndIntervalAndStrategy("BTC-EUR", _, StrategyType.EMA_CROSSOVER, 5) >> []

        when:
        def result = riskManager.validateForStrategy(BigDecimal.valueOf(5_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then:
        result.approved()
        result.positionSizeEur() == new BigDecimal("100.00000000")
    }

    def "legacy validate() delegates to validateForStrategy with primary pair and null strategy"() {
        given:
        positionRepository.countByStatusAndPairAndIntervalAndStrategyName(OrderStatus.OPEN, "BTC-EUR", _, null) >> 0
        tradeRepository.sumPnlSinceAndPairAndIntervalAndStrategy(_ as LocalDateTime, "BTC-EUR", _, null) >> BigDecimal.ZERO
        tradeRepository.findRecentTradesByPairAndIntervalAndStrategy("BTC-EUR", _, null, 5) >> []

        when:
        def result = riskManager.validate(BigDecimal.valueOf(10_000))

        then:
        result.approved()
    }

    // ─── currentStatusForStrategy ─────────────────────────────────────────────

    def "currentStatusForStrategy returns correct snapshot values"() {
        given:
        positionRepository.countByStatusAndPairAndIntervalAndStrategyName(OrderStatus.OPEN, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> 2
        tradeRepository.sumPnlSinceAndPairAndIntervalAndStrategy(_ as LocalDateTime, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> BigDecimal.valueOf(-100)
        tradeRepository.findRecentTradesByPairAndIntervalAndStrategy("BTC-EUR", _, StrategyType.EMA_CROSSOVER, 5) >> [losingTrade(), losingTrade()]

        when:
        def status = riskManager.currentStatusForStrategy(BigDecimal.valueOf(10_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then:
        status.openPositions() == 2
        status.dailyPnl() == BigDecimal.valueOf(-100)
        status.consecutiveLosses() == 2
        !status.dailyCircuitBreakerTripped()   // -100 is within -500 limit
        !status.consecutiveCircuitBreakerTripped()  // 2 < 5
        !status.positionLimitReached()         // 2 < 3
        !status.anyCircuitBreakerTripped()
    }

    def "anyCircuitBreakerTripped is true when daily limit breached"() {
        given:
        positionRepository.countByStatusAndPairAndIntervalAndStrategyName(OrderStatus.OPEN, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> 0
        tradeRepository.sumPnlSinceAndPairAndIntervalAndStrategy(_ as LocalDateTime, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> BigDecimal.valueOf(-600)
        tradeRepository.findRecentTradesByPairAndIntervalAndStrategy("BTC-EUR", _, StrategyType.EMA_CROSSOVER, 5) >> []

        when:
        def status = riskManager.currentStatusForStrategy(BigDecimal.valueOf(10_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then:
        status.dailyCircuitBreakerTripped()
        status.anyCircuitBreakerTripped()
    }

    def "positionLimitReached is true when at max concurrent positions"() {
        given:
        positionRepository.countByStatusAndPairAndIntervalAndStrategyName(OrderStatus.OPEN, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> 3
        tradeRepository.sumPnlSinceAndPairAndIntervalAndStrategy(_ as LocalDateTime, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> BigDecimal.ZERO
        tradeRepository.findRecentTradesByPairAndIntervalAndStrategy("BTC-EUR", _, StrategyType.EMA_CROSSOVER, 5) >> []

        when:
        def status = riskManager.currentStatusForStrategy(BigDecimal.valueOf(10_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then:
        status.positionLimitReached()
    }

    // ─── snapshotAll + statusFromSnapshot (per-cycle path) ──────────────────

    def "snapshotAll fans out three aggregate queries into a per-key map"() {
        given: "aggregate rows across two pairs, one interval, one strategy"
        positionRepository.countByStatusGroupedByPairIntervalStrategy(OrderStatus.OPEN) >> [
            (["BTC-EUR", "15m", StrategyType.EMA_CROSSOVER, 2L] as Object[]),
            (["ETH-EUR", "15m", StrategyType.EMA_CROSSOVER, 0L] as Object[])
        ]
        tradeRepository.sumPnlSinceGroupedByPairIntervalStrategy(_ as LocalDateTime) >> [
            (["BTC-EUR", "15m", StrategyType.EMA_CROSSOVER, BigDecimal.valueOf(-150)] as Object[]),
            (["ETH-EUR", "15m", StrategyType.EMA_CROSSOVER, BigDecimal.valueOf(75)]   as Object[])
        ]
        tradeRepository.findByExecutedAtAfterOrderByExecutedAtDesc(_ as LocalDateTime) >> [
            tradeFor("BTC-EUR", "15m", StrategyType.EMA_CROSSOVER, -10),
            tradeFor("BTC-EUR", "15m", StrategyType.EMA_CROSSOVER, -5),
            tradeFor("BTC-EUR", "15m", StrategyType.EMA_CROSSOVER, 20),    // streak breaker
            tradeFor("BTC-EUR", "15m", StrategyType.EMA_CROSSOVER, -30),   // older, must not count
            tradeFor("ETH-EUR", "15m", StrategyType.EMA_CROSSOVER, 15)
        ]

        when:
        def snapshot = riskManager.snapshotAll()

        then: "counts / sums land in the right bucket"
        snapshot.openPositions()[new RiskManager.Key("BTC-EUR", "15m", StrategyType.EMA_CROSSOVER)] == 2L
        snapshot.openPositions()[new RiskManager.Key("ETH-EUR", "15m", StrategyType.EMA_CROSSOVER)] == 0L
        snapshot.dailyPnls()[new RiskManager.Key("BTC-EUR", "15m", StrategyType.EMA_CROSSOVER)]     == BigDecimal.valueOf(-150)
        snapshot.dailyPnls()[new RiskManager.Key("ETH-EUR", "15m", StrategyType.EMA_CROSSOVER)]     == BigDecimal.valueOf(75)

        and: "consecutive-loss streak stops at the first win for that key"
        snapshot.consecutiveLosses()[new RiskManager.Key("BTC-EUR", "15m", StrategyType.EMA_CROSSOVER)] == 2
        !snapshot.consecutiveLosses().containsKey(new RiskManager.Key("ETH-EUR", "15m", StrategyType.EMA_CROSSOVER))
    }

    def "statusFromSnapshot derives circuit-breaker flags without touching the DB"() {
        given: "a snapshot where BTC-EUR is over the daily loss limit"
        def key = new RiskManager.Key("BTC-EUR", "15m", StrategyType.EMA_CROSSOVER)
        def snapshot = new RiskManager.CycleSnapshot(
            [(key): 0L],
            [(key): BigDecimal.valueOf(-600)],
            [(key): 0]
        )

        when:
        def status = riskManager.statusFromSnapshot(snapshot,
                BigDecimal.valueOf(10_000), "BTC-EUR", "15m", StrategyType.EMA_CROSSOVER)

        then:
        status.dailyPnl() == BigDecimal.valueOf(-600)
        status.dailyCircuitBreakerTripped()
        status.anyCircuitBreakerTripped()

        and: "no repository calls happen for status computation from snapshot"
        0 * positionRepository._
        0 * tradeRepository._
    }

    def "statusFromSnapshot returns zeros for unknown keys (no activity this cycle)"() {
        given: "an empty snapshot — no trades, no positions"
        def snapshot = new RiskManager.CycleSnapshot([:], [:], [:])

        when:
        def status = riskManager.statusFromSnapshot(snapshot,
                BigDecimal.valueOf(10_000), "SOL-EUR", "1h", StrategyType.MACD)

        then:
        status.openPositions() == 0
        status.dailyPnl() == BigDecimal.ZERO
        status.consecutiveLosses() == 0
        !status.anyCircuitBreakerTripped()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static TradingConfig buildConfig() {
        def risk = new TradingConfig.Risk()
        risk.maxConcurrentPositions  = 3
        risk.maxDailyLossPct         = BigDecimal.valueOf(5)
        risk.maxConsecutiveLosses    = 5
        risk.maxPositionPct          = BigDecimal.valueOf(2)
        risk.takeProfitPct           = BigDecimal.valueOf(5)
        risk.stopLossPct             = BigDecimal.valueOf(3)

        def strategy = new TradingConfig.Strategy()
        strategy.emaShortPeriod = 9
        strategy.emaLongPeriod  = 21
        strategy.rsiPeriod      = 14
        strategy.rsiOverbought  = 70
        strategy.rsiOversold    = 30

        def cfg = new TradingConfig()
        cfg.pairs                   = ["BTC-EUR", "ETH-EUR"]
        cfg.mode                    = "PAPER"
        cfg.paperBalance            = BigDecimal.valueOf(10_000)
        cfg.pollingIntervalSeconds  = 30
        cfg.risk                    = risk
        cfg.strategy                = strategy
        return cfg
    }

    private static Trade losingTrade() {
        def t = new Trade()
        t.pnl = BigDecimal.valueOf(-50)
        return t
    }

    private static Trade winningTrade() {
        def t = new Trade()
        t.pnl = BigDecimal.valueOf(100)
        return t
    }

    private static Trade tradeFor(String pair, String interval, StrategyType strategy, long pnl) {
        def t = new Trade()
        t.pair = pair
        t.interval = interval
        t.strategyName = strategy
        t.pnl = BigDecimal.valueOf(pnl)
        return t
    }
}
