package com.stefo.revolut_trading_bot.service

import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.model.dto.ConfigUpdateRequest
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal

class ConfigServiceSpec extends Specification {

    TradingConfig tradingConfig = buildConfig()
    BotEventService botEventService = Mock()

    @Subject
    ConfigService service = new ConfigService(tradingConfig, botEventService)

    def "UpdateConfigs applies all provided fields"() {
        given:
        def req = new ConfigUpdateRequest(
                BigDecimal.valueOf(3),   // maxPositionPct
                5,                       // maxConcurrentPositions
                BigDecimal.valueOf(10),  // maxDailyLossPct
                8,                       // maxConsecutiveLosses
                BigDecimal.valueOf(7),   // takeProfitPct
                BigDecimal.valueOf(4),   // stopLossPct
                12,                      // emaShortPeriod
                26,                      // emaLongPeriod
                7,                       // rsiPeriod
                75,                      // rsiOverbought
                25,                      // rsiOversold
                BigDecimal.valueOf(20_000)  // paperBalance
        )

        when:
        service.UpdateConfigs(req)

        then:
        tradingConfig.risk.maxPositionPct          == BigDecimal.valueOf(3)
        tradingConfig.risk.maxConcurrentPositions  == 5
        tradingConfig.risk.maxDailyLossPct         == BigDecimal.valueOf(10)
        tradingConfig.risk.maxConsecutiveLosses    == 8
        tradingConfig.risk.takeProfitPct           == BigDecimal.valueOf(7)
        tradingConfig.risk.stopLossPct             == BigDecimal.valueOf(4)
        tradingConfig.strategy.emaShortPeriod      == 12
        tradingConfig.strategy.emaLongPeriod       == 26
        tradingConfig.strategy.rsiPeriod           == 7
        tradingConfig.strategy.rsiOverbought       == 75
        tradingConfig.strategy.rsiOversold         == 25
        tradingConfig.paperBalance                 == BigDecimal.valueOf(20_000)
    }

    def "UpdateConfigs leaves existing values unchanged when field is null"() {
        given: "only update takeProfitPct; everything else stays at original"
        def originalMaxPositionPct  = tradingConfig.risk.maxPositionPct
        def originalPaperBalance    = tradingConfig.paperBalance
        def req = new ConfigUpdateRequest(
                null, null, null, null,
                BigDecimal.valueOf(8), // only takeProfitPct
                null, null, null, null, null, null, null
        )

        when:
        service.UpdateConfigs(req)

        then:
        tradingConfig.risk.takeProfitPct           == BigDecimal.valueOf(8)
        tradingConfig.risk.maxPositionPct          == originalMaxPositionPct
        tradingConfig.paperBalance                 == originalPaperBalance
    }

    def "UpdateConfigs with all-null request leaves everything unchanged"() {
        given:
        def originalMaxPositionPct = tradingConfig.risk.maxPositionPct
        def req = new ConfigUpdateRequest(
                null, null, null, null, null, null,
                null, null, null, null, null, null
        )

        when:
        service.UpdateConfigs(req)

        then:
        tradingConfig.risk.maxPositionPct == originalMaxPositionPct
    }

    def "UpdateConfigs updates paper balance independently"() {
        given:
        def req = new ConfigUpdateRequest(
                null, null, null, null, null, null,
                null, null, null, null, null,
                BigDecimal.valueOf(15_000)
        )

        when:
        service.UpdateConfigs(req)

        then:
        tradingConfig.paperBalance == BigDecimal.valueOf(15_000)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static TradingConfig buildConfig() {
        def risk = new TradingConfig.Risk()
        risk.maxPositionPct         = BigDecimal.valueOf(2)
        risk.maxConcurrentPositions = 3
        risk.maxDailyLossPct        = BigDecimal.valueOf(5)
        risk.maxConsecutiveLosses   = 5
        risk.takeProfitPct          = BigDecimal.valueOf(5)
        risk.stopLossPct            = BigDecimal.valueOf(3)

        def strategy = new TradingConfig.Strategy()
        strategy.emaShortPeriod = 9
        strategy.emaLongPeriod  = 21
        strategy.rsiPeriod      = 14
        strategy.rsiOverbought  = 70
        strategy.rsiOversold    = 30

        def cfg = new TradingConfig()
        cfg.pairs = ["BTC-EUR"]
        cfg.mode  = "PAPER"
        cfg.paperBalance = BigDecimal.valueOf(10_000)
        cfg.pollingIntervalSeconds = 30
        cfg.risk     = risk
        cfg.strategy = strategy
        cfg
    }
}
