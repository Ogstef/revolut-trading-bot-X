package com.stefo.revolut_trading_bot.scheduler

import com.stefo.revolut_trading_bot.alert.AlertService
import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.execution.OrderExecutionService
import com.stefo.revolut_trading_bot.market.MarketDataClient
import com.stefo.revolut_trading_bot.market.MarketDataService
import com.stefo.revolut_trading_bot.model.enums.SignalType
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import com.stefo.revolut_trading_bot.risk.RiskManager
import com.stefo.revolut_trading_bot.service.TripleConfigService
import com.stefo.revolut_trading_bot.strategy.Signal
import com.stefo.revolut_trading_bot.strategy.SignalEngine
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal
import java.time.Instant

class TradingLoopSpec extends Specification {

    SignalEngine signalEngine = Mock()
    OrderExecutionService orderExecutionService = Mock()
    RiskManager riskManager = Mock()
    MarketDataService marketDataService = Mock()
    MarketDataClient marketDataClient = Mock()
    TradingConfig tradingConfig = buildConfig()
    BotStateService botStateService = Mock()
    AlertService alertService = Mock()
    TripleConfigService tripleConfigService = Mock()

    @Subject
    TradingLoop loop = new TradingLoop(
            signalEngine, orderExecutionService, riskManager,
            marketDataService, marketDataClient, tradingConfig,
            botStateService, alertService, tripleConfigService
    )

    def "disabled triple skips executeSignal but keeps monitorPositions armed"() {
        given:
        botStateService.isActive() >> true
        signalEngine.registeredStrategies() >> [StrategyType.RSI_MOMENTUM]
        signalEngine.evaluateAllPairsAndPersist() >> [buyFor("BTC-EUR", "15m", StrategyType.TRIPLE_EMA)]
        riskManager.snapshotAll() >> emptySnapshot()
        riskManager.statusFromSnapshot(_, _, _, _, _) >> healthyStatus()
        marketDataService.getCurrentPriceForPair("BTC-EUR") >> BigDecimal.valueOf(50_000)
        tripleConfigService.isEnabled("BTC-EUR", "15m", StrategyType.TRIPLE_EMA) >> false

        when:
        loop.run()

        then: "monitor still runs — soft-disable contract"
        1 * orderExecutionService.monitorPositions(_, "BTC-EUR", "15m", StrategyType.TRIPLE_EMA)

        and: "no new entries"
        0 * orderExecutionService.executeSignal(_, _, _)
    }

    def "enabled triple lets executeSignal through"() {
        given:
        botStateService.isActive() >> true
        signalEngine.registeredStrategies() >> [StrategyType.RSI_MOMENTUM]
        signalEngine.evaluateAllPairsAndPersist() >> [buyFor("BTC-EUR", "15m", StrategyType.RSI_MOMENTUM)]
        riskManager.snapshotAll() >> emptySnapshot()
        riskManager.statusFromSnapshot(_, _, _, _, _) >> healthyStatus()
        marketDataService.getCurrentPriceForPair("BTC-EUR") >> BigDecimal.valueOf(50_000)
        tripleConfigService.isEnabled("BTC-EUR", "15m", StrategyType.RSI_MOMENTUM) >> true

        when:
        loop.run()

        then:
        1 * orderExecutionService.monitorPositions(_, "BTC-EUR", "15m", StrategyType.RSI_MOMENTUM)
        1 * orderExecutionService.executeSignal(_, _, _)
    }

    def "circuit-breaker still wins over the disable gate (returns before checking)"() {
        given:
        botStateService.isActive() >> true
        signalEngine.registeredStrategies() >> [StrategyType.RSI_MOMENTUM]
        signalEngine.evaluateAllPairsAndPersist() >> [buyFor("BTC-EUR", "15m", StrategyType.RSI_MOMENTUM)]
        riskManager.snapshotAll() >> emptySnapshot()
        riskManager.statusFromSnapshot(_, _, _, _, _) >> trippedStatus()
        marketDataService.getCurrentPriceForPair("BTC-EUR") >> BigDecimal.valueOf(50_000)

        when:
        loop.run()

        then: "circuit breaker fires; disable gate is never even consulted"
        0 * tripleConfigService.isEnabled(_, _, _)
        0 * orderExecutionService.executeSignal(_, _, _)
        1 * orderExecutionService.monitorPositions(_, _, _, _)
        1 * alertService.circuitBreakerTripped(_)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static Signal buyFor(String pair, String interval, StrategyType strategy) {
        return new Signal(
                SignalType.BUY,
                BigDecimal.valueOf(80),
                "test signal",
                pair,
                interval,
                strategy,
                Instant.now(),
                null, null, null,
                BigDecimal.valueOf(50_000)
        )
    }

    private static RiskManager.CycleSnapshot emptySnapshot() {
        return new RiskManager.CycleSnapshot([:], [:], [:])
    }

    private static RiskManager.RiskStatus healthyStatus() {
        return new RiskManager.RiskStatus(
                0L, BigDecimal.ZERO, 0, false, false, false
        )
    }

    private static RiskManager.RiskStatus trippedStatus() {
        return new RiskManager.RiskStatus(
                0L, BigDecimal.valueOf(-1000), 0, true, false, false
        )
    }

    private static TradingConfig buildConfig() {
        def cfg = new TradingConfig()
        cfg.pairs = ["BTC-EUR"]
        cfg.intervals = [15]
        cfg.mode = "PAPER"
        cfg.paperBalance = BigDecimal.valueOf(10_000)
        cfg.pollingIntervalSeconds = 30
        cfg.risk = new TradingConfig.Risk(
                maxPositionPct: BigDecimal.valueOf(2),
                maxConcurrentPositions: 3,
                maxDailyLossPct: BigDecimal.valueOf(10),
                maxConsecutiveLosses: 5,
                takeProfitPct: BigDecimal.valueOf(5),
                stopLossPct: BigDecimal.valueOf(3))
        cfg.strategy = new TradingConfig.Strategy(
                emaShortPeriod: 9, emaLongPeriod: 21, rsiPeriod: 14,
                rsiOverbought: 70, rsiOversold: 30)
        return cfg
    }
}
