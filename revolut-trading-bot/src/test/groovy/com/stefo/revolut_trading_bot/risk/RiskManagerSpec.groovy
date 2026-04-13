package com.stefo.revolut_trading_bot.risk

import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.model.entity.Trade
import com.stefo.revolut_trading_bot.model.enums.OrderStatus
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import com.stefo.revolut_trading_bot.repository.PositionRepository
import com.stefo.revolut_trading_bot.repository.TradeRepository
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal
import java.time.LocalDateTime

class RiskManagerSpec extends Specification {

    PositionRepository positionRepository = Mock()
    TradeRepository    tradeRepository    = Mock()
    TradingConfig      config             = buildConfig()

    @Subject
    RiskManager riskManager = new RiskManager(positionRepository, tradeRepository, config)

    // ─── validateForStrategy ─────────────────────────────────────────────────

    def "approved when all risk checks pass"() {
        given:
        positionRepository.countByStatusAndPairAndStrategyName(OrderStatus.OPEN, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> 0
        tradeRepository.sumPnlSinceAndPairAndStrategy(_ as LocalDateTime, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> BigDecimal.ZERO
        tradeRepository.findRecentTradesByPairAndStrategy("BTC-EUR", StrategyType.EMA_CROSSOVER, 5) >> []

        when:
        def result = riskManager.validateForStrategy(BigDecimal.valueOf(10_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then:
        result.approved()
        result.positionSizeEur() == new BigDecimal("200.00000000")
    }

    def "rejected when max concurrent positions reached"() {
        given: "already at the position cap (3)"
        positionRepository.countByStatusAndPairAndStrategyName(OrderStatus.OPEN, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> 3

        when:
        def result = riskManager.validateForStrategy(BigDecimal.valueOf(10_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then:
        !result.approved()
        result.reason().contains("Max concurrent positions")
    }

    def "rejected when daily loss breaches circuit breaker"() {
        given:
        positionRepository.countByStatusAndPairAndStrategyName(OrderStatus.OPEN, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> 0
        // Daily PnL is -600 EUR, threshold is -5% of 10_000 = -500 EUR
        tradeRepository.sumPnlSinceAndPairAndStrategy(_ as LocalDateTime, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> BigDecimal.valueOf(-600)

        when:
        def result = riskManager.validateForStrategy(BigDecimal.valueOf(10_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then:
        !result.approved()
        result.reason().contains("Daily loss circuit breaker")
    }

    def "rejected when consecutive loss circuit breaker trips"() {
        given:
        positionRepository.countByStatusAndPairAndStrategyName(OrderStatus.OPEN, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> 0
        tradeRepository.sumPnlSinceAndPairAndStrategy(_ as LocalDateTime, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> BigDecimal.ZERO
        tradeRepository.findRecentTradesByPairAndStrategy("BTC-EUR", StrategyType.EMA_CROSSOVER, 5) >> [
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
        positionRepository.countByStatusAndPairAndStrategyName(OrderStatus.OPEN, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> 0
        tradeRepository.sumPnlSinceAndPairAndStrategy(_ as LocalDateTime, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> BigDecimal.ZERO
        tradeRepository.findRecentTradesByPairAndStrategy("BTC-EUR", StrategyType.EMA_CROSSOVER, 5) >> [
            losingTrade(), losingTrade(), winningTrade(), losingTrade(), losingTrade()
        ]

        when:
        def result = riskManager.validateForStrategy(BigDecimal.valueOf(10_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then: "only 2 consecutive — below threshold of 5 → approved"
        result.approved()
    }

    def "position size is 2% of available balance"() {
        given:
        positionRepository.countByStatusAndPairAndStrategyName(OrderStatus.OPEN, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> 0
        tradeRepository.sumPnlSinceAndPairAndStrategy(_ as LocalDateTime, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> BigDecimal.ZERO
        tradeRepository.findRecentTradesByPairAndStrategy("BTC-EUR", StrategyType.EMA_CROSSOVER, 5) >> []

        when:
        def result = riskManager.validateForStrategy(BigDecimal.valueOf(5_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then:
        result.approved()
        result.positionSizeEur() == new BigDecimal("100.00000000")
    }

    def "legacy validate() delegates to validateForStrategy with primary pair and null strategy"() {
        given:
        positionRepository.countByStatus(OrderStatus.OPEN) >> 0
        tradeRepository.sumPnlSince(_ as LocalDateTime) >> BigDecimal.ZERO
        tradeRepository.findRecentTradesByPair("BTC-EUR", 5) >> []

        when:
        def result = riskManager.validate(BigDecimal.valueOf(10_000))

        then:
        result.approved()
    }

    // ─── currentStatusForStrategy ─────────────────────────────────────────────

    def "currentStatusForStrategy returns correct snapshot values"() {
        given:
        positionRepository.countByStatusAndPairAndStrategyName(OrderStatus.OPEN, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> 2
        tradeRepository.sumPnlSinceAndPairAndStrategy(_ as LocalDateTime, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> BigDecimal.valueOf(-100)
        tradeRepository.findRecentTradesByPairAndStrategy("BTC-EUR", StrategyType.EMA_CROSSOVER, 5) >> [losingTrade(), losingTrade()]

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
        positionRepository.countByStatusAndPairAndStrategyName(OrderStatus.OPEN, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> 0
        tradeRepository.sumPnlSinceAndPairAndStrategy(_ as LocalDateTime, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> BigDecimal.valueOf(-600)
        tradeRepository.findRecentTradesByPairAndStrategy("BTC-EUR", StrategyType.EMA_CROSSOVER, 5) >> []

        when:
        def status = riskManager.currentStatusForStrategy(BigDecimal.valueOf(10_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then:
        status.dailyCircuitBreakerTripped()
        status.anyCircuitBreakerTripped()
    }

    def "positionLimitReached is true when at max concurrent positions"() {
        given:
        positionRepository.countByStatusAndPairAndStrategyName(OrderStatus.OPEN, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> 3
        tradeRepository.sumPnlSinceAndPairAndStrategy(_ as LocalDateTime, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> BigDecimal.ZERO
        tradeRepository.findRecentTradesByPairAndStrategy("BTC-EUR", StrategyType.EMA_CROSSOVER, 5) >> []

        when:
        def status = riskManager.currentStatusForStrategy(BigDecimal.valueOf(10_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then:
        status.positionLimitReached()
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
}
