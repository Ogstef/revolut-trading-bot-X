package com.stefo.revolut_trading_bot.service

import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.market.MarketDataService
import com.stefo.revolut_trading_bot.model.entity.Position
import com.stefo.revolut_trading_bot.model.enums.OrderSide
import com.stefo.revolut_trading_bot.model.enums.OrderStatus
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import com.stefo.revolut_trading_bot.risk.RiskManager
import com.stefo.revolut_trading_bot.strategy.SignalEngine
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal

class StrategyServiceSpec extends Specification {

    TradingConfig    tradingConfig    = buildConfig()
    SignalEngine     signalEngine     = Mock()
    RiskManager      riskManager      = Mock()
    MarketDataService marketDataService = Mock()
    PositionService  positionService  = Mock()

    @Subject
    StrategyService service = new StrategyService(
            tradingConfig, signalEngine, riskManager, marketDataService, positionService)

    // ─── getResult ────────────────────────────────────────────────────────────

    def "getResult uses primary pair when none provided"() {
        given:
        signalEngine.registeredStrategies() >> [StrategyType.EMA_CROSSOVER]
        riskManager.currentStatusForStrategy(_, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >>
                buildRiskStatus(0, BigDecimal.ZERO, 0)

        when:
        def result = service.getResult(null)

        then:
        result.size() == 1
        result[0].pair == "BTC-EUR"
    }

    def "getResult uses provided pair"() {
        given:
        signalEngine.registeredStrategies() >> [StrategyType.EMA_CROSSOVER]
        riskManager.currentStatusForStrategy(_, "ETH-EUR", _, StrategyType.EMA_CROSSOVER) >>
                buildRiskStatus(0, BigDecimal.ZERO, 0)

        when:
        def result = service.getResult("ETH-EUR")

        then:
        result[0].pair == "ETH-EUR"
    }

    def "getResult returns one entry per registered strategy"() {
        given:
        signalEngine.registeredStrategies() >> [StrategyType.EMA_CROSSOVER, StrategyType.MACD, StrategyType.BOLLINGER]
        riskManager.currentStatusForStrategy(_, "BTC-EUR", _, _) >> buildRiskStatus(0, BigDecimal.ZERO, 0)

        when:
        def result = service.getResult("BTC-EUR")

        then:
        result.size() == 3
    }

    def "getResult includes expected keys in each entry map"() {
        given:
        signalEngine.registeredStrategies() >> [StrategyType.EMA_CROSSOVER]
        riskManager.currentStatusForStrategy(_, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >>
                buildRiskStatus(2, BigDecimal.valueOf(-50), 1)

        when:
        def result = service.getResult("BTC-EUR")
        def entry = result[0]

        then:
        entry.containsKey("pair")
        entry.containsKey("name")
        entry.containsKey("displayName")
        entry.containsKey("openPositions")
        entry.containsKey("dailyPnl")
        entry.containsKey("consecutiveLosses")
        entry.containsKey("circuitBreakerActive")
        entry.openPositions == 2L
        entry.dailyPnl == BigDecimal.valueOf(-50)
    }

    def "getResult uses strategy-specific balance from config when available"() {
        given:
        def btcBalances = [(StrategyType.EMA_CROSSOVER): BigDecimal.valueOf(7_000)]
        tradingConfig.strategyBalances = ["BTC-EUR": btcBalances]
        signalEngine.registeredStrategies() >> [StrategyType.EMA_CROSSOVER]
        riskManager.currentStatusForStrategy(BigDecimal.valueOf(7_000), "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >>
                buildRiskStatus(0, BigDecimal.ZERO, 0)

        when:
        def result = service.getResult("BTC-EUR")

        then:
        result.size() == 1
        1 * riskManager.currentStatusForStrategy(BigDecimal.valueOf(7_000), "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >>
                buildRiskStatus(0, BigDecimal.ZERO, 0)
    }

    def "getResult falls back to paper balance when no strategy-specific balance"() {
        given:
        tradingConfig.strategyBalances = [:]
        signalEngine.registeredStrategies() >> [StrategyType.EMA_CROSSOVER]

        when:
        service.getResult("BTC-EUR")

        then:
        1 * riskManager.currentStatusForStrategy(BigDecimal.valueOf(10_000), "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >>
                buildRiskStatus(0, BigDecimal.ZERO, 0)
    }

    // ─── getPositionForStrategy ───────────────────────────────────────────────

    def "getPositionForStrategy uses primary pair when none provided"() {
        given:
        marketDataService.getCurrentPriceForPair("BTC-EUR") >> BigDecimal.valueOf(52_000)

        when:
        def result = service.getPositionForStrategy(null, StrategyType.EMA_CROSSOVER)

        then:
        1 * positionService.getOpenPositions("BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> []
        result.isEmpty()
    }

    def "getPositionForStrategy maps positions to PositionView with current price"() {
        given:
        def position = openBuyPosition("BTC-EUR", BigDecimal.valueOf(50_000), new BigDecimal("0.02"))
        marketDataService.getCurrentPriceForPair("BTC-EUR") >> BigDecimal.valueOf(52_500)
        positionService.getOpenPositions("BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> [position]

        when:
        def views = service.getPositionForStrategy("BTC-EUR", StrategyType.EMA_CROSSOVER)

        then:
        views.size() == 1
        views[0].currentPrice() == BigDecimal.valueOf(52_500)
        views[0].unrealisedPnl() == new BigDecimal("50.00")
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static TradingConfig buildConfig() {
        def risk = new TradingConfig.Risk()
        risk.maxConcurrentPositions = 3
        risk.maxDailyLossPct = BigDecimal.valueOf(5)
        risk.maxConsecutiveLosses = 5
        risk.maxPositionPct = BigDecimal.valueOf(2)
        risk.takeProfitPct = BigDecimal.valueOf(5)
        risk.stopLossPct   = BigDecimal.valueOf(3)

        def cfg = new TradingConfig()
        cfg.pairs = ["BTC-EUR", "ETH-EUR"]
        cfg.mode  = "PAPER"
        cfg.paperBalance = BigDecimal.valueOf(10_000)
        cfg.pollingIntervalSeconds = 30
        cfg.risk     = risk
        cfg.strategy = new TradingConfig.Strategy()
        cfg.strategyBalances = [:]
        cfg
    }

    private static RiskManager.RiskStatus buildRiskStatus(long openPos, BigDecimal dailyPnl, int consecutive) {
        new RiskManager.RiskStatus(openPos, dailyPnl, consecutive, false, false, false)
    }

    private static Position openBuyPosition(String pair, BigDecimal entry, BigDecimal qty) {
        Position.builder()
                .pair(pair)
                .side(OrderSide.BUY)
                .entryPrice(entry)
                .quantity(qty)
                .takeProfit(entry.multiply(BigDecimal.valueOf(1.05)))
                .stopLoss(entry.multiply(BigDecimal.valueOf(0.97)))
                .status(OrderStatus.OPEN)
                .strategyName(StrategyType.EMA_CROSSOVER)
                .build()
    }
}
