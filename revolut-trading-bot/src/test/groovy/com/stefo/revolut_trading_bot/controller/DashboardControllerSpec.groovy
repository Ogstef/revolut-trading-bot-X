package com.stefo.revolut_trading_bot.controller

import com.stefo.revolut_trading_bot.alert.AlertService
import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.model.dto.BotStatusResponse
import com.stefo.revolut_trading_bot.model.dto.PnlBreakdown
import com.stefo.revolut_trading_bot.model.dto.ConfigUpdateRequest
import com.stefo.revolut_trading_bot.model.dto.PositionView
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import com.stefo.revolut_trading_bot.portfolio.TradingStats
import com.stefo.revolut_trading_bot.portfolio.TradeService
import com.stefo.revolut_trading_bot.repository.TradeRepository
import com.stefo.revolut_trading_bot.scheduler.BotStateService
import com.stefo.revolut_trading_bot.service.BotStatusService
import com.stefo.revolut_trading_bot.service.ConfigService
import com.stefo.revolut_trading_bot.service.PositionService
import com.stefo.revolut_trading_bot.service.SignalService
import com.stefo.revolut_trading_bot.service.StrategyService
import org.springframework.http.HttpStatus
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal
import java.time.Instant

/**
 * Pure-Spock unit tests for DashboardController.
 *
 * The controller is instantiated directly with Spock mocks — no Spring context,
 * no Mockito, no MockMvc. We call each controller method and assert on the
 * returned ResponseEntity (status code + body).
 */
class DashboardControllerSpec extends Specification {

    BotStateService   botStateService   = Mock()
    TradingConfig     tradingConfig     = Stub()
    TradeService      tradeService      = Mock()
    TradeRepository   tradeRepository   = Mock()
    AlertService      alertService      = Mock()
    BotStatusService  botStatusService  = Mock()
    PositionService   positionService   = Mock()
    ConfigService     configService     = Mock()
    StrategyService   strategyService   = Mock()
    SignalService     signalService     = Mock()

    @Subject
    DashboardController controller = new DashboardController(
            botStateService, tradingConfig, tradeService, tradeRepository,
            alertService, botStatusService, positionService, configService,
            strategyService, signalService)

    def setup() {
        tradingConfig.primaryPair() >> "BTC-EUR"
        tradingConfig.getPairs()    >> ["BTC-EUR", "ETH-EUR"]
    }

    // ─── GET /api/status ──────────────────────────────────────────────────────

    def "status() returns 200 with bot status"() {
        given:
        def expected = new BotStatusResponse(true, "PAPER", "BTC-EUR",
                0L, BigDecimal.ZERO, 0, false, Instant.now())
        botStatusService.getStatus() >> expected

        when:
        def response = controller.status()

        then:
        response.statusCode == HttpStatus.OK
        response.body == expected
    }

    def "status() reflects stopped bot (running=false)"() {
        given:
        def stopped = new BotStatusResponse(false, "PAPER", "BTC-EUR",
                0L, BigDecimal.ZERO, 0, false, Instant.now())
        botStatusService.getStatus() >> stopped

        when:
        def response = controller.status()

        then:
        response.statusCode == HttpStatus.OK
        !response.body.running()
    }

    // ─── GET /api/positions ───────────────────────────────────────────────────

    def "positions() delegates to positionService and returns 200"() {
        given:
        positionService.getPositions("BTC-EUR") >> []

        when:
        def response = controller.positions("BTC-EUR")

        then:
        response.statusCode == HttpStatus.OK
        response.body == []
    }

    def "positions() passes null pair to positionService when not provided"() {
        given:
        positionService.getPositions(null) >> []

        when:
        def response = controller.positions(null)

        then:
        response.statusCode == HttpStatus.OK
        1 * positionService.getPositions(null) >> []
    }

    // ─── GET /api/trades ─────────────────────────────────────────────────────

    def "trades() uses primary pair when pair is null"() {
        given:
        tradeRepository.findRecentTradesByPair("BTC-EUR", 50) >> []

        when:
        def response = controller.trades(50, null)

        then:
        response.statusCode == HttpStatus.OK
        1 * tradeRepository.findRecentTradesByPair("BTC-EUR", 50) >> []
    }

    def "trades() uses provided pair"() {
        given:
        tradeRepository.findRecentTradesByPair("ETH-EUR", 20) >> []

        when:
        def response = controller.trades(20, "ETH-EUR")

        then:
        response.statusCode == HttpStatus.OK
        1 * tradeRepository.findRecentTradesByPair("ETH-EUR", 20) >> []
    }

    // ─── GET /api/stats ───────────────────────────────────────────────────────

    def "stats() returns aggregate trading stats"() {
        given:
        def stats = emptyStats()
        tradeService.getStats() >> stats

        when:
        def response = controller.stats()

        then:
        response.statusCode == HttpStatus.OK
        response.body == stats
    }

    // ─── GET /api/pnl ─────────────────────────────────────────────────────────

    def "pnl() returns PnL breakdown"() {
        given:
        def breakdown = new PnlBreakdown(
                BigDecimal.valueOf(50), BigDecimal.valueOf(200),
                BigDecimal.valueOf(800), BigDecimal.valueOf(3000))
        tradeService.getPnlBreakdown() >> breakdown

        when:
        def response = controller.pnl()

        then:
        response.statusCode == HttpStatus.OK
        response.body.daily() == BigDecimal.valueOf(50)
        response.body.allTime() == BigDecimal.valueOf(3000)
    }

    // ─── POST /api/emergency-stop ─────────────────────────────────────────────

    def "emergencyStop() stops the bot and returns 200"() {
        when:
        def response = controller.emergencyStop()

        then:
        response.statusCode == HttpStatus.OK
        1 * botStateService.stop()
        1 * alertService.botStopped(_)
    }

    def "emergencyStop() response body contains helpful message"() {
        when:
        def response = controller.emergencyStop()

        then:
        response.body.contains("resume")
        1 * botStateService.stop()
        1 * alertService.botStopped(_)
    }

    // ─── POST /api/resume ─────────────────────────────────────────────────────

    def "resume() resumes the bot and returns 200"() {
        when:
        def response = controller.resume()

        then:
        response.statusCode == HttpStatus.OK
        1 * botStateService.resume()
        1 * alertService.botResumed(_)
    }

    // ─── POST /api/config ─────────────────────────────────────────────────────

    def "updateConfig() delegates to configService and returns 200"() {
        given:
        def req = new ConfigUpdateRequest(
                null, null, null, null,
                BigDecimal.valueOf(6), BigDecimal.valueOf(4),
                null, null, null, null, null, null)

        when:
        def response = controller.updateConfig(req)

        then:
        response.statusCode == HttpStatus.OK
        1 * configService.UpdateConfigs(req)
    }

    // ─── GET /api/pairs ───────────────────────────────────────────────────────

    def "pairs() returns all configured pairs with base and quote assets"() {
        when:
        def response = controller.pairs()

        then:
        response.statusCode == HttpStatus.OK
        response.body.size() == 2
        response.body[0].pair       == "BTC-EUR"
        response.body[0].baseAsset  == "BTC"
        response.body[0].quoteAsset == "EUR"
        response.body[1].pair       == "ETH-EUR"
        response.body[1].baseAsset  == "ETH"
    }

    // ─── GET /api/strategies ─────────────────────────────────────────────────

    def "strategies() delegates to strategyService with the pair filter"() {
        given:
        strategyService.getResult("BTC-EUR") >> [[name: "EMA_CROSSOVER"]]

        when:
        def response = controller.strategies("BTC-EUR")

        then:
        response.statusCode == HttpStatus.OK
        response.body.size() == 1
    }

    def "strategies() passes null pair when not provided"() {
        given:
        strategyService.getResult(null) >> []

        when:
        def response = controller.strategies(null)

        then:
        response.statusCode == HttpStatus.OK
    }

    // ─── GET /api/strategies/{name}/positions ─────────────────────────────────

    def "strategyPositions() returns position views for the given strategy"() {
        given:
        strategyService.getPositionForStrategy("BTC-EUR", StrategyType.EMA_CROSSOVER) >> []

        when:
        def response = controller.strategyPositions(StrategyType.EMA_CROSSOVER, "BTC-EUR")

        then:
        response.statusCode == HttpStatus.OK
    }

    // ─── GET /api/strategies/{name}/trades ───────────────────────────────────

    def "strategyTrades() uses primary pair when none provided"() {
        given:
        tradeRepository.findRecentTradesByPairAndStrategy("BTC-EUR", StrategyType.MACD, 50) >> []

        when:
        def response = controller.strategyTrades(StrategyType.MACD, 50, null)

        then:
        response.statusCode == HttpStatus.OK
        1 * tradeRepository.findRecentTradesByPairAndStrategy("BTC-EUR", StrategyType.MACD, 50) >> []
    }

    // ─── GET /api/strategies/{name}/stats ────────────────────────────────────

    def "strategyStats() returns stats for the given strategy"() {
        given:
        tradeService.getStatsForStrategy(StrategyType.BOLLINGER) >> emptyStats()

        when:
        def response = controller.strategyStats(StrategyType.BOLLINGER)

        then:
        response.statusCode == HttpStatus.OK
        response.body.totalTrades() == 0
    }

    // ─── GET /api/strategies/{name}/pnl ─────────────────────────────────────

    def "strategyPnl() returns PnL breakdown for the strategy"() {
        given:
        tradeService.getPnlBreakdownForStrategy(StrategyType.RSI_MOMENTUM) >>
                new PnlBreakdown(BigDecimal.valueOf(10), BigDecimal.valueOf(40),
                        BigDecimal.valueOf(100), BigDecimal.valueOf(500))

        when:
        def response = controller.strategyPnl(StrategyType.RSI_MOMENTUM)

        then:
        response.statusCode == HttpStatus.OK
        response.body.daily() == BigDecimal.valueOf(10)
    }

    // ─── GET /api/strategies/{name}/signals ──────────────────────────────────

    def "strategySignals() delegates to signalService"() {
        given:
        signalService.getSignalStrategies("BTC-EUR", StrategyType.EMA_CROSSOVER, 20) >> []

        when:
        def response = controller.strategySignals(StrategyType.EMA_CROSSOVER, 20, "BTC-EUR")

        then:
        response.statusCode == HttpStatus.OK
    }

    // ─── GET /api/signals/summary ─────────────────────────────────────────────

    def "signalsSummary() uses primary pair when none provided"() {
        given:
        signalService.getSummary(null) >> []

        when:
        def response = controller.signalsSummary(null)

        then:
        response.statusCode == HttpStatus.OK
        1 * signalService.getSummary(null) >> []
    }

    def "signalsSummary() returns signal counts per strategy"() {
        given:
        def summary = [
                [strategy: "EMA_CROSSOVER", signalType: "BUY", count: 15L],
                [strategy: "MACD",          signalType: "SELL", count: 7L]
        ]
        signalService.getSummary("BTC-EUR") >> summary

        when:
        def response = controller.signalsSummary("BTC-EUR")

        then:
        response.statusCode == HttpStatus.OK
        response.body.size() == 2
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static TradingStats emptyStats() {
        new TradingStats(0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
    }
}
