package com.stefo.revolut_trading_bot.controller

import com.stefo.revolut_trading_bot.alert.AlertService
import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.model.dto.TradeHistoryEntry
import com.stefo.revolut_trading_bot.model.enums.OrderSide
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import com.stefo.revolut_trading_bot.model.enums.TradingMode
import com.stefo.revolut_trading_bot.portfolio.TradeService
import com.stefo.revolut_trading_bot.repository.CandlestickRepository
import com.stefo.revolut_trading_bot.repository.TradeRepository
import com.stefo.revolut_trading_bot.scheduler.BotStateService
import com.stefo.revolut_trading_bot.service.BotStatusService
import com.stefo.revolut_trading_bot.service.ConfigService
import com.stefo.revolut_trading_bot.service.BotEventService
import com.stefo.revolut_trading_bot.service.FearGreedService
import com.stefo.revolut_trading_bot.service.PositionService
import com.stefo.revolut_trading_bot.service.SignalService
import com.stefo.revolut_trading_bot.service.StatsAggregationService
import com.stefo.revolut_trading_bot.service.StrategyService
import org.springframework.http.HttpStatus
import spock.lang.Specification
import spock.lang.Subject

import java.time.LocalDateTime

/**
 * Pure-Spock unit tests for the new GET /api/strategies/{name}/history endpoint.
 * Verifies enrichment from the joined Position and the optional date-range filter.
 */
class DashboardControllerHistorySpec extends Specification {

    BotStateService          botStateService          = Mock()
    TradingConfig            tradingConfig            = Stub()
    TradeService             tradeService             = Mock()
    TradeRepository          tradeRepository          = Mock()
    AlertService             alertService             = Mock()
    BotStatusService         botStatusService         = Mock()
    PositionService          positionService          = Mock()
    ConfigService            configService            = Mock()
    StrategyService          strategyService          = Mock()
    SignalService            signalService            = Mock()
    FearGreedService         fearGreedService         = Mock()
    StatsAggregationService  statsAggregationService  = Mock()
    BotEventService          botEventService          = Mock()
    CandlestickRepository    candlestickRepository    = Mock()

    @Subject
    DashboardController controller = new DashboardController(
            botStateService, tradingConfig, tradeService, tradeRepository,
            alertService, botStatusService, positionService, configService,
            strategyService, signalService, fearGreedService,
            statsAggregationService, botEventService, candlestickRepository)

    def setup() {
        tradingConfig.primaryPair()     >> "BTC-EUR"
        tradingConfig.primaryInterval() >> "15m"
    }

    def "strategyHistory() returns empty list when repository has no matches"() {
        given:
        strategyService.getTradeHistory(null, null, StrategyType.EMA_CROSSOVER, null, null) >> []

        when:
        def response = controller.strategyHistory(
                StrategyType.EMA_CROSSOVER, null, null, null, null)

        then:
        response.statusCode == HttpStatus.OK
        response.body == []
    }

    def "strategyHistory() enriches each trade with parent-position fields"() {
        given:
        def opened = LocalDateTime.of(2026, 4, 1, 12, 0)
        def closed = LocalDateTime.of(2026, 4, 1, 13, 30)

        def entry = new TradeHistoryEntry(
                11L, "BTC-EUR", "15m", OrderSide.BUY,
                new BigDecimal("100.00"), new BigDecimal("103.00"),
                new BigDecimal("0.5"), new BigDecimal("1.50"), new BigDecimal("3.00"),
                StrategyType.EMA_CROSSOVER, "SIGNAL_EXIT", TradingMode.PAPER,
                opened, closed,
                "EMA9 crossed above EMA21 | RSI=52.3",
                new BigDecimal("105.00"), new BigDecimal("97.00"),
                opened,
                5400L, new BigDecimal("1.0000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("1.50"), new BigDecimal("3.00"))

        strategyService.getTradeHistory("BTC-EUR", "15m", StrategyType.EMA_CROSSOVER, null, null) >> [entry]

        when:
        def response = controller.strategyHistory(
                StrategyType.EMA_CROSSOVER, "BTC-EUR", "15m", null, null)

        then:
        response.statusCode == HttpStatus.OK
        response.body.size() == 1
        with(response.body[0]) {
            id() == 11L
            entrySignalReason() == "EMA9 crossed above EMA21 | RSI=52.3"
            takeProfit() == new BigDecimal("105.00")
            stopLoss() == new BigDecimal("97.00")
            openedAt() == opened
            holdingDurationSeconds() == 5400L
            rMultiple() == new BigDecimal("1.0000")
        }
    }

    def "strategyHistory() handles a trade whose position is null (legacy data)"() {
        given:
        def entry = new TradeHistoryEntry(
                99L, "BTC-EUR", "15m", OrderSide.SELL,
                new BigDecimal("100.00"), new BigDecimal("99.00"),
                new BigDecimal("1.0"), new BigDecimal("-1.00"), new BigDecimal("-1.00"),
                StrategyType.MACD, "MANUAL", TradingMode.PAPER,
                LocalDateTime.now(), null,
                null, null, null, null,
                null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("-1.00"), new BigDecimal("-1.00"))

        strategyService.getTradeHistory(null, null, StrategyType.MACD, null, null) >> [entry]

        when:
        def response = controller.strategyHistory(
                StrategyType.MACD, null, null, null, null)

        then:
        response.statusCode == HttpStatus.OK
        response.body.size() == 1
        with(response.body[0]) {
            id() == 99L
            entrySignalReason() == null
            takeProfit() == null
            stopLoss() == null
            openedAt() == null
            holdingDurationSeconds() == null
            rMultiple() == null
        }
    }

    def "strategyHistory() forwards from/to bounds to the repository"() {
        given:
        def from = LocalDateTime.of(2026, 4, 1, 0, 0)
        def to   = LocalDateTime.of(2026, 4, 14, 23, 59)

        when:
        def response = controller.strategyHistory(
                StrategyType.BOLLINGER, "ETH-EUR", "1h", from, to)

        then:
        response.statusCode == HttpStatus.OK
        1 * strategyService.getTradeHistory("ETH-EUR", "1h", StrategyType.BOLLINGER, from, to) >> []
    }

    def "strategyHistory() defaults pair and interval when not provided"() {
        when:
        def response = controller.strategyHistory(
                StrategyType.RSI_MOMENTUM, null, null, null, null)

        then:
        response.statusCode == HttpStatus.OK
        1 * strategyService.getTradeHistory(null, null, StrategyType.RSI_MOMENTUM, null, null) >> []
    }
}
