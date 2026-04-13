package com.stefo.revolut_trading_bot.service

import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.risk.RiskManager
import com.stefo.revolut_trading_bot.scheduler.BotStateService
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal

class BotStatusServiceSpec extends Specification {

    TradingConfig   tradingConfig   = buildConfig()
    RiskManager     riskManager     = Mock()
    BotStateService botStateService = Mock()

    @Subject
    BotStatusService service = new BotStatusService(tradingConfig, riskManager, botStateService)

    def "getStatus returns running=true when bot is active"() {
        given:
        botStateService.isActive() >> true
        riskManager.currentStatus(BigDecimal.valueOf(10_000)) >> buildRiskStatus(0, BigDecimal.ZERO, 0, false, false, false)

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
        riskManager.currentStatus(BigDecimal.valueOf(10_000)) >> buildRiskStatus(0, BigDecimal.ZERO, 0, false, false, false)

        when:
        def status = service.getStatus()

        then:
        !status.running()
    }

    def "getStatus reflects open position count from risk manager"() {
        given:
        botStateService.isActive() >> true
        riskManager.currentStatus(BigDecimal.valueOf(10_000)) >> buildRiskStatus(2, BigDecimal.valueOf(-50), 1, false, false, false)

        when:
        def status = service.getStatus()

        then:
        status.openPositions()    == 2
        status.dailyPnl()         == BigDecimal.valueOf(-50)
        status.consecutiveLosses() == 1
        !status.circuitBreakerOn()
    }

    def "getStatus reflects circuit breaker active when daily limit tripped"() {
        given:
        botStateService.isActive() >> true
        riskManager.currentStatus(BigDecimal.valueOf(10_000)) >> buildRiskStatus(0, BigDecimal.valueOf(-600), 0, true, false, false)

        when:
        def status = service.getStatus()

        then:
        status.circuitBreakerOn()
    }

    def "getStatus reflects circuit breaker active when consecutive losses tripped"() {
        given:
        botStateService.isActive() >> true
        riskManager.currentStatus(BigDecimal.valueOf(10_000)) >> buildRiskStatus(0, BigDecimal.ZERO, 5, false, true, false)

        when:
        def status = service.getStatus()

        then:
        status.circuitBreakerOn()
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

    private static RiskManager.RiskStatus buildRiskStatus(long openPos, BigDecimal dailyPnl,
                                                           int consecutive,
                                                           boolean dailyCb, boolean consecutiveCb,
                                                           boolean posLimit) {
        new RiskManager.RiskStatus(openPos, dailyPnl, consecutive, dailyCb, consecutiveCb, posLimit)
    }
}
