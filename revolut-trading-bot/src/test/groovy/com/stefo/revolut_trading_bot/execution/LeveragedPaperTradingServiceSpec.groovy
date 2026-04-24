package com.stefo.revolut_trading_bot.execution

import com.stefo.revolut_trading_bot.alert.AlertService
import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.model.entity.Position
import com.stefo.revolut_trading_bot.model.entity.Trade
import com.stefo.revolut_trading_bot.model.enums.OrderSide
import com.stefo.revolut_trading_bot.model.enums.OrderStatus
import com.stefo.revolut_trading_bot.model.enums.SignalType
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import com.stefo.revolut_trading_bot.model.enums.TradingVehicle
import com.stefo.revolut_trading_bot.portfolio.VehicleBalanceService
import com.stefo.revolut_trading_bot.repository.PositionRepository
import com.stefo.revolut_trading_bot.repository.TradeRepository
import com.stefo.revolut_trading_bot.risk.TakeProfitStopLossManager
import com.stefo.revolut_trading_bot.service.BotEventService
import com.stefo.revolut_trading_bot.strategy.Signal
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime

class LeveragedPaperTradingServiceSpec extends Specification {

    PositionRepository      positionRepository = Mock()
    TradeRepository         tradeRepository    = Mock()
    TradingConfig           config             = buildConfig()
    TakeProfitStopLossManager tpslManager      = new TakeProfitStopLossManager(config)
    VehicleBalanceService   vehicleBalanceService = Mock()
    LeverageCooldownService cooldownService    = Mock()
    AlertService            alertService       = Mock()
    BotEventService         botEventService    = Mock()

    @Subject
    LeveragedPaperTradingService service = new LeveragedPaperTradingService(
            positionRepository, tradeRepository, tpslManager, vehicleBalanceService,
            cooldownService, config, alertService, botEventService)

    // ─── open ─────────────────────────────────────────────────────────────────

    @Unroll
    def "opens #vehicle long: notional = collateral × leverage, qty = notional / price"() {
        given:
        def signal = buySignal("BTC-EUR")
        positionRepository.save(_) >> { Position p -> p.id = 1L; p }
        tradeRepository.save(_) >> { Trade t -> t }

        when:
        def p = service.openPosition(signal, BigDecimal.valueOf(1_000), BigDecimal.valueOf(50_000), vehicle)

        then:
        p.vehicle == vehicle
        p.leverage == (short) leverage
        p.collateral == BigDecimal.valueOf(1_000)
        p.notional == new BigDecimal("${1_000 * leverage}.00000000")
        p.quantity == new BigDecimal("${1_000 * leverage}").divide(BigDecimal.valueOf(50_000), 8, java.math.RoundingMode.HALF_UP)
        p.side == OrderSide.BUY
        p.status == OrderStatus.OPEN
        p.liquidationPrice != null
        p.liquidationPrice < p.entryPrice                   // long — liq below entry
        1 * vehicleBalanceService.debit("BTC-EUR", StrategyType.EMA_CROSSOVER, vehicle, BigDecimal.valueOf(1_000))
        1 * alertService.positionOpened(_)

        where:
        vehicle               | leverage
        TradingVehicle.LEV_3X | 3
        TradingVehicle.LEV_5X | 5
        TradingVehicle.LEV_10X| 10
    }

    def "opens a LEV_5X short — TP below entry, SL above, liq above entry"() {
        given:
        def signal = sellSignal("BTC-EUR")
        positionRepository.save(_) >> { Position p -> p }
        tradeRepository.save(_) >> { Trade t -> t }

        when:
        def p = service.openPosition(signal, BigDecimal.valueOf(1_000), BigDecimal.valueOf(100), TradingVehicle.LEV_5X)

        then:
        p.side == OrderSide.SELL
        p.takeProfit < p.entryPrice
        p.stopLoss   > p.entryPrice
        p.liquidationPrice > p.entryPrice
    }

    def "refuses to open SPOT on the leveraged service"() {
        given:
        def signal = buySignal("BTC-EUR")

        when:
        service.openPosition(signal, BigDecimal.valueOf(1_000), BigDecimal.valueOf(50_000), TradingVehicle.SPOT)

        then:
        thrown(IllegalArgumentException)
    }

    // ─── close ────────────────────────────────────────────────────────────────

    def "5x long closed at +10% returns ≈ collateral + 50% PnL (minus fees/slippage/funding)"() {
        given: "entry 100, price 110 = +10% move on 5x long → gross 50% of collateral"
        def position = openLong(TradingVehicle.LEV_5X, BigDecimal.valueOf(100), BigDecimal.valueOf(1_000))
        def openTrade = openedTrade(position)
        tradeRepository.findOpenTradeByPosition(position) >> Optional.of(openTrade)
        tradeRepository.save(_) >> { Trade t -> t }
        positionRepository.save(_) >> { Position p -> p }

        when:
        def result = service.closePosition(position, BigDecimal.valueOf(110), "TP_HIT")

        then:
        result.pnl == new BigDecimal("500.00000000")       // 10 × 50 qty = 500 gross
        result.netPnl < result.pnl                         // fees reduce it
        result.netPnl > BigDecimal.ZERO
        !result.liquidated
        1 * vehicleBalanceService.credit("BTC-EUR", StrategyType.EMA_CROSSOVER, TradingVehicle.LEV_5X, _)
        1 * cooldownService.recordClose(position)
        1 * alertService.positionClosed(_, _)
        0 * botEventService.recordPositionLiquidated(_)
    }

    def "liquidated trade sets liquidated=true and records a POSITION_LIQUIDATED event"() {
        given:
        def position = openLong(TradingVehicle.LEV_10X, BigDecimal.valueOf(100), BigDecimal.valueOf(1_000))
        def openTrade = openedTrade(position)
        tradeRepository.findOpenTradeByPosition(position) >> Optional.of(openTrade)
        tradeRepository.save(_) >> { Trade t -> t }
        positionRepository.save(_) >> { Position p -> p }

        when:
        def result = service.closePosition(position, BigDecimal.valueOf(90), "LIQUIDATED")

        then:
        result.liquidated
        result.exitReason == "LIQUIDATED"
        1 * botEventService.recordPositionLiquidated(_)
    }

    def "short closed at lower price returns positive PnL"() {
        given: "shorted 100, exit at 95 → profit for short"
        def position = openShort(TradingVehicle.LEV_5X, BigDecimal.valueOf(100), BigDecimal.valueOf(1_000))
        def openTrade = openedTrade(position)
        tradeRepository.findOpenTradeByPosition(position) >> Optional.of(openTrade)
        tradeRepository.save(_) >> { Trade t -> t }
        positionRepository.save(_) >> { Position p -> p }

        when:
        def result = service.closePosition(position, BigDecimal.valueOf(95), "TP_HIT")

        then:
        result.pnl > BigDecimal.ZERO
    }

    def "credit on close never goes below zero for a wiped position"() {
        given: "liquidation at 90 on a 10x long entered at 100 — collateral all but gone"
        def position = openLong(TradingVehicle.LEV_10X, BigDecimal.valueOf(100), BigDecimal.valueOf(1_000))
        def openTrade = openedTrade(position)
        tradeRepository.findOpenTradeByPosition(position) >> Optional.of(openTrade)
        tradeRepository.save(_) >> { Trade t -> t }
        positionRepository.save(_) >> { Position p -> p }
        BigDecimal captured = null
        vehicleBalanceService.credit(_, _, _, _) >> { String p, StrategyType s, TradingVehicle v, BigDecimal amt ->
            captured = amt
            return null
        }

        when:
        service.closePosition(position, BigDecimal.valueOf(85), "LIQUIDATED")

        then:
        captured != null
        captured >= BigDecimal.ZERO
    }

    // ─── funding ──────────────────────────────────────────────────────────────

    def "accrueFunding adds notional × rate × elapsed/8h and updates lastFundingAt"() {
        given: "open position with notional=5000, last funding 8h ago → 1 full 8h period at rate=0.0001 = 0.50 EUR"
        def now = LocalDateTime.now()
        def position = Position.builder()
                .pair("BTC-EUR")
                .interval("1h")
                .strategyName(StrategyType.EMA_CROSSOVER)
                .side(OrderSide.BUY)
                .entryPrice(BigDecimal.valueOf(100))
                .quantity(BigDecimal.valueOf(50))
                .takeProfit(BigDecimal.valueOf(112))
                .stopLoss(BigDecimal.valueOf(96))
                .status(OrderStatus.OPEN)
                .vehicle(TradingVehicle.LEV_5X)
                .leverage((short) 5)
                .collateral(BigDecimal.valueOf(1_000))
                .notional(BigDecimal.valueOf(5_000))
                .liquidationPrice(BigDecimal.valueOf(82))
                .fundingFeesAccrued(BigDecimal.ZERO)
                .lastFundingAt(now.minusHours(8))
                .openedAt(now.minusHours(8))
                .build()
        positionRepository.save(_) >> { Position p -> p }

        when:
        service.accrueFunding(position, now)

        then: "5000 × 0.0001 × 1.0 ≈ 0.50"
        position.fundingFeesAccrued.compareTo(new BigDecimal("0.49000000")) >= 0
        position.fundingFeesAccrued.compareTo(new BigDecimal("0.51000000")) <= 0
        position.lastFundingAt == now
    }

    def "accrueFunding is a no-op for spot positions"() {
        given:
        def position = Position.builder()
                .vehicle(TradingVehicle.SPOT)
                .leverage((short) 1)
                .build()

        when:
        service.accrueFunding(position, LocalDateTime.now())

        then:
        0 * positionRepository.save(_)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static Signal buySignal(String pair) {
        new Signal(SignalType.BUY, BigDecimal.valueOf(75), "BUY signal",
                pair, "1h", StrategyType.EMA_CROSSOVER, Instant.now(),
                null, null, null, BigDecimal.valueOf(50_000))
    }

    private static Signal sellSignal(String pair) {
        new Signal(SignalType.SELL, BigDecimal.valueOf(70), "SELL signal",
                pair, "1h", StrategyType.EMA_CROSSOVER, Instant.now(),
                null, null, null, BigDecimal.valueOf(50_000))
    }

    private static Position openLong(TradingVehicle vehicle, BigDecimal entry, BigDecimal collateral) {
        BigDecimal notional = collateral.multiply(BigDecimal.valueOf(vehicle.leverage))
        Position.builder()
                .pair("BTC-EUR")
                .interval("1h")
                .side(OrderSide.BUY)
                .entryPrice(entry)
                .quantity(notional.divide(entry, 8, java.math.RoundingMode.HALF_UP))
                .takeProfit(entry.multiply(BigDecimal.valueOf(1.12)).setScale(8, java.math.RoundingMode.HALF_UP))
                .stopLoss(entry.multiply(BigDecimal.valueOf(0.96)).setScale(8, java.math.RoundingMode.HALF_UP))
                .status(OrderStatus.OPEN)
                .strategyName(StrategyType.EMA_CROSSOVER)
                .vehicle(vehicle)
                .leverage((short) vehicle.leverage)
                .collateral(collateral)
                .notional(notional)
                .liquidationPrice(entry.multiply(BigDecimal.valueOf(0.91)).setScale(8, java.math.RoundingMode.HALF_UP))
                .fundingFeesAccrued(BigDecimal.ZERO)
                .build()
    }

    private static Position openShort(TradingVehicle vehicle, BigDecimal entry, BigDecimal collateral) {
        BigDecimal notional = collateral.multiply(BigDecimal.valueOf(vehicle.leverage))
        Position.builder()
                .pair("BTC-EUR")
                .interval("1h")
                .side(OrderSide.SELL)
                .entryPrice(entry)
                .quantity(notional.divide(entry, 8, java.math.RoundingMode.HALF_UP))
                .takeProfit(entry.multiply(BigDecimal.valueOf(0.88)).setScale(8, java.math.RoundingMode.HALF_UP))
                .stopLoss(entry.multiply(BigDecimal.valueOf(1.04)).setScale(8, java.math.RoundingMode.HALF_UP))
                .status(OrderStatus.OPEN)
                .strategyName(StrategyType.EMA_CROSSOVER)
                .vehicle(vehicle)
                .leverage((short) vehicle.leverage)
                .collateral(collateral)
                .notional(notional)
                .liquidationPrice(entry.multiply(BigDecimal.valueOf(1.09)).setScale(8, java.math.RoundingMode.HALF_UP))
                .fundingFeesAccrued(BigDecimal.ZERO)
                .build()
    }

    private static Trade openedTrade(Position position) {
        Trade.builder()
                .position(position)
                .pair(position.pair)
                .interval(position.interval)
                .side(position.side)
                .entryPrice(position.entryPrice)
                .quantity(position.quantity)
                .strategyName(StrategyType.EMA_CROSSOVER)
                .vehicle(position.vehicle)
                .leverage(position.leverage)
                .collateral(position.collateral)
                .entryFee(new BigDecimal("4.50000000"))
                .entrySlippage(new BigDecimal("2.50000000"))
                .build()
    }

    private static TradingConfig buildConfig() {
        def cfg = new TradingConfig()
        cfg.pairs  = ["BTC-EUR"]
        cfg.mode   = "PAPER"
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

        def costs = new TradingConfig.Costs()
        costs.feeRate      = new BigDecimal("0.0009")
        costs.slippageRate = new BigDecimal("0.0005")
        cfg.costs = costs

        def lev = new TradingConfig.Leverage()
        lev.enabled = true
        lev.ratios  = [3, 5, 10]
        lev.pairs   = ["BTC-EUR"]
        lev.intervals = [60]
        lev.collateralPerStrategy = new BigDecimal("1000.00")
        lev.maintenanceMarginPct  = new BigDecimal("0.5")
        lev.fundingRatePer8h      = new BigDecimal("0.0001")
        cfg.leverage = lev

        cfg
    }
}
