package com.stefo.revolut_trading_bot.execution

import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.model.entity.Position
import com.stefo.revolut_trading_bot.model.enums.OrderSide
import com.stefo.revolut_trading_bot.model.enums.OrderStatus
import com.stefo.revolut_trading_bot.model.enums.SignalType
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import com.stefo.revolut_trading_bot.repository.PositionRepository
import com.stefo.revolut_trading_bot.risk.RiskManager
import com.stefo.revolut_trading_bot.risk.RiskValidationResult
import com.stefo.revolut_trading_bot.risk.TakeProfitStopLossManager
import com.stefo.revolut_trading_bot.strategy.Signal
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal
import java.time.Instant

class OrderExecutionServiceSpec extends Specification {

    RiskManager             riskManager         = Mock()
    PaperTradingService     paperTradingService  = Mock()
    TakeProfitStopLossManager tpslManager        = Mock()
    PositionRepository      positionRepository   = Mock()
    TradingConfig           config               = buildConfig()

    @Subject
    OrderExecutionService service = new OrderExecutionService(
            riskManager, paperTradingService, tpslManager, positionRepository, config)

    // ─── executeSignal ────────────────────────────────────────────────────────

    def "HOLD signal: no action taken, returns empty"() {
        given:
        def signal = signal(SignalType.HOLD, "BTC-EUR", StrategyType.EMA_CROSSOVER)

        when:
        def result = service.executeSignal(signal, BigDecimal.valueOf(10_000), BigDecimal.valueOf(50_000))

        then:
        !result.isPresent()
        0 * riskManager._
        0 * paperTradingService._
    }

    def "SELL signal: closes all open positions for the pair and strategy"() {
        given:
        def signal = signal(SignalType.SELL, "BTC-EUR", StrategyType.EMA_CROSSOVER)
        def openPositions = [openPosition()]
        positionRepository.findByStatusAndPairAndIntervalAndStrategyName(OrderStatus.OPEN, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> openPositions

        when:
        def result = service.executeSignal(signal, BigDecimal.valueOf(10_000), BigDecimal.valueOf(50_000))

        then:
        !result.isPresent()
        1 * paperTradingService.closePosition(openPositions[0], BigDecimal.valueOf(50_000), TakeProfitStopLossManager.EXIT_SIGNAL)
    }

    def "SELL signal: no positions to close — no exception"() {
        given:
        def signal = signal(SignalType.SELL, "BTC-EUR", StrategyType.EMA_CROSSOVER)
        positionRepository.findByStatusAndPairAndIntervalAndStrategyName(OrderStatus.OPEN, "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >> []

        when:
        def result = service.executeSignal(signal, BigDecimal.valueOf(10_000), BigDecimal.valueOf(50_000))

        then:
        !result.isPresent()
        0 * paperTradingService.closePosition(_, _, _)
    }

    def "BUY signal: opens position when risk approved"() {
        given:
        def signal = signal(SignalType.BUY, "BTC-EUR", StrategyType.EMA_CROSSOVER)
        def expectedPosition = openPosition()
        riskManager.validateForStrategy(BigDecimal.valueOf(10_000), "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >>
                RiskValidationResult.approved(BigDecimal.valueOf(200))
        paperTradingService.openPosition(signal, BigDecimal.valueOf(200), BigDecimal.valueOf(50_000)) >> expectedPosition

        when:
        def result = service.executeSignal(signal, BigDecimal.valueOf(10_000), BigDecimal.valueOf(50_000))

        then:
        result.isPresent()
        result.get() == expectedPosition
    }

    def "BUY signal: returns empty when risk rejected"() {
        given:
        def signal = signal(SignalType.BUY, "BTC-EUR", StrategyType.EMA_CROSSOVER)
        riskManager.validateForStrategy(BigDecimal.valueOf(10_000), "BTC-EUR", _, StrategyType.EMA_CROSSOVER) >>
                RiskValidationResult.rejected("Max positions reached")

        when:
        def result = service.executeSignal(signal, BigDecimal.valueOf(10_000), BigDecimal.valueOf(50_000))

        then:
        !result.isPresent()
        0 * paperTradingService.openPosition(_, _, _)
    }

    // ─── monitorPositions (pair + strategy scoped) ───────────────────────────

    def "monitorPositions closes position when TP is hit"() {
        given:
        def position = openPosition()
        positionRepository.findByStatusAndPairAndStrategyName(OrderStatus.OPEN, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> [position]
        tpslManager.checkExitCondition(position, BigDecimal.valueOf(55_000)) >> Optional.of("TP_HIT")

        when:
        service.monitorPositions(BigDecimal.valueOf(55_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then:
        1 * paperTradingService.closePosition(position, BigDecimal.valueOf(55_000), "TP_HIT")
    }

    def "monitorPositions does not close position when no TP or SL hit"() {
        given:
        def position = openPosition()
        positionRepository.findByStatusAndPairAndStrategyName(OrderStatus.OPEN, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> [position]
        tpslManager.checkExitCondition(position, BigDecimal.valueOf(51_000)) >> Optional.empty()

        when:
        service.monitorPositions(BigDecimal.valueOf(51_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then:
        0 * paperTradingService.closePosition(_, _, _)
    }

    def "monitorPositions with no open positions is a no-op"() {
        given:
        positionRepository.findByStatusAndPairAndStrategyName(OrderStatus.OPEN, "BTC-EUR", StrategyType.EMA_CROSSOVER) >> []

        when:
        service.monitorPositions(BigDecimal.valueOf(50_000), "BTC-EUR", StrategyType.EMA_CROSSOVER)

        then:
        0 * tpslManager._
        0 * paperTradingService._
    }

    def "monitorPositions legacy (all positions) closes on SL hit"() {
        given:
        def position = openPosition()
        positionRepository.findByStatus(OrderStatus.OPEN) >> [position]
        tpslManager.checkExitCondition(position, BigDecimal.valueOf(47_000)) >> Optional.of("SL_HIT")

        when:
        service.monitorPositions(BigDecimal.valueOf(47_000))

        then:
        1 * paperTradingService.closePosition(position, BigDecimal.valueOf(47_000), "SL_HIT")
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static Signal signal(SignalType type, String pair, StrategyType strategyType) {
        new Signal(type, BigDecimal.valueOf(75), "test signal",
                pair, null, strategyType, Instant.now(),
                null, null, null, BigDecimal.valueOf(50_000))
    }

    private static Position openPosition() {
        Position.builder()
                .pair("BTC-EUR")
                .side(OrderSide.BUY)
                .entryPrice(BigDecimal.valueOf(50_000))
                .quantity(new BigDecimal("0.02"))
                .takeProfit(BigDecimal.valueOf(52_500))
                .stopLoss(BigDecimal.valueOf(48_500))
                .status(OrderStatus.OPEN)
                .strategyName(StrategyType.EMA_CROSSOVER)
                .build()
    }

    private static TradingConfig buildConfig() {
        def risk = new TradingConfig.Risk()
        risk.maxConcurrentPositions = 3
        risk.maxDailyLossPct = BigDecimal.valueOf(5)
        risk.maxConsecutiveLosses = 5
        risk.maxPositionPct = BigDecimal.valueOf(2)
        risk.takeProfitPct = BigDecimal.valueOf(5)
        risk.stopLossPct = BigDecimal.valueOf(3)

        def cfg = new TradingConfig()
        cfg.pairs = ["BTC-EUR"]
        cfg.mode = "PAPER"
        cfg.paperBalance = BigDecimal.valueOf(10_000)
        cfg.pollingIntervalSeconds = 30
        cfg.risk = risk
        cfg.strategy = new TradingConfig.Strategy()
        cfg
    }
}
