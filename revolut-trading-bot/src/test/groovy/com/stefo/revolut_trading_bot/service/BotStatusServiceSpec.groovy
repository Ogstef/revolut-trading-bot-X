package com.stefo.revolut_trading_bot.service

import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.model.enums.OrderStatus
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import com.stefo.revolut_trading_bot.repository.PositionRepository
import com.stefo.revolut_trading_bot.repository.TradeRepository
import com.stefo.revolut_trading_bot.risk.RiskManager
import com.stefo.revolut_trading_bot.scheduler.BotStateService
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal

class BotStatusServiceSpec extends Specification {

    TradingConfig      tradingConfig      = buildConfig()
    RiskManager        riskManager        = Mock()
    BotStateService    botStateService    = Mock()
    PositionRepository positionRepository = Mock()
    TradeRepository    tradeRepository    = Mock()

    @Subject
    BotStatusService service = new BotStatusService(
            tradingConfig, riskManager, botStateService, positionRepository, tradeRepository)

    def "getStatus returns running=true when bot is active"() {
        given:
        botStateService.isActive() >> true
        positionRepository.countByStatus(OrderStatus.OPEN) >> 0L
        tradeRepository.sumPnlSince(_) >> BigDecimal.ZERO
        riskManager.currentStatusForStrategy(BigDecimal.valueOf(10_000), "BTC-EUR", "15m", StrategyType.EMA_CROSSOVER) >>
                buildRiskStatus(0, BigDecimal.ZERO, 0, false, false, false)

        when:
        def status = service.getStatus()

        then:
        status.running()
        status.mode() == "PAPER"
        status.pair() == "BTC-EUR"
        status.reportedAt() != null
    }

    def "getStatus returns running=false when bot is stopped"() {
        given:
        botStateService.isActive() >> false
        positionRepository.countByStatus(OrderStatus.OPEN) >> 0L
        tradeRepository.sumPnlSince(_) >> BigDecimal.ZERO
        riskManager.currentStatusForStrategy(_, _, _, _) >>
                buildRiskStatus(0, BigDecimal.ZERO, 0, false, false, false)

        when:
        def status = service.getStatus()

        then:
        !status.running()
    }

    def "getStatus reports global open position count from the repository, not the primary strategy"() {
        given: "Five positions are open across all strategies; RiskManager sees only the primary slot"
        botStateService.isActive() >> true
        positionRepository.countByStatus(OrderStatus.OPEN) >> 5L
        tradeRepository.sumPnlSince(_) >> BigDecimal.valueOf(-50)
        riskManager.currentStatusForStrategy(_, _, _, _) >>
                buildRiskStatus(1, BigDecimal.ZERO, 1, false, false, false)

        when:
        def status = service.getStatus()

        then:
        status.openPositions()     == 5L
        status.dailyPnl()          == BigDecimal.valueOf(-50)
        status.consecutiveLosses() == 1
        !status.circuitBreakerOn()
    }

    def "getStatus reflects circuit breaker active when daily limit tripped on primary strategy"() {
        given:
        botStateService.isActive() >> true
        positionRepository.countByStatus(OrderStatus.OPEN) >> 0L
        tradeRepository.sumPnlSince(_) >> BigDecimal.valueOf(-600)
        riskManager.currentStatusForStrategy(_, _, _, _) >>
                buildRiskStatus(0, BigDecimal.valueOf(-600), 0, true, false, false)

        when:
        def status = service.getStatus()

        then:
        status.circuitBreakerOn()
    }

    def "getStatus reflects circuit breaker active when consecutive losses tripped on primary strategy"() {
        given:
        botStateService.isActive() >> true
        positionRepository.countByStatus(OrderStatus.OPEN) >> 0L
        tradeRepository.sumPnlSince(_) >> BigDecimal.ZERO
        riskManager.currentStatusForStrategy(_, _, _, _) >>
                buildRiskStatus(0, BigDecimal.ZERO, 5, false, true, false)

        when:
        def status = service.getStatus()

        then:
        status.circuitBreakerOn()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static TradingConfig buildConfig() {
        def cfg = new TradingConfig()
        cfg.pairs = ["BTC-EUR"]
        cfg.intervals = [15]
        cfg.mode  = "PAPER"
        cfg.paperBalance = BigDecimal.valueOf(10_000)
        cfg.pollingIntervalSeconds = 30
        cfg.primaryStrategy = StrategyType.EMA_CROSSOVER
        cfg.risk     = new TradingConfig.Risk()
        cfg.strategy = new TradingConfig.Strategy()
        cfg
    }

    private static RiskManager.RiskStatus buildRiskStatus(long openPos, BigDecimal dailyPnl,
                                                           int consecutive,
                                                           boolean dailyCb, boolean consecutiveCb,
                                                           boolean posLimit) {
        new RiskManager.RiskStatus(openPos, dailyPnl, consecutive, dailyCb, consecutiveCb, posLimit)
    }
}
