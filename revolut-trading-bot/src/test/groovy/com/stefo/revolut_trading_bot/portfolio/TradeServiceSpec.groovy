package com.stefo.revolut_trading_bot.portfolio

import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.model.entity.Trade
import com.stefo.revolut_trading_bot.model.enums.OrderSide
import com.stefo.revolut_trading_bot.model.enums.StrategyType
import com.stefo.revolut_trading_bot.repository.TradeRepository
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal
import java.time.LocalDateTime

class TradeServiceSpec extends Specification {

    TradeRepository tradeRepository = Mock()
    TradingConfig   config          = buildConfig()

    @Subject
    TradeService service = new TradeService(tradeRepository, config)

    // ─── getStats — global ────────────────────────────────────────────────────

    def "getStats returns empty stats when no closed trades exist"() {
        given:
        tradeRepository.findByPairOrderByExecutedAtDesc("BTC-EUR") >> []

        when:
        def stats = service.getStats()

        then:
        stats.totalTrades()   == 0
        stats.winningTrades() == 0
        stats.losingTrades()  == 0
        stats.winRate()       == BigDecimal.ZERO
        stats.totalPnl()      == BigDecimal.ZERO
        stats.expectancy()    == BigDecimal.ZERO
    }

    def "getStats ignores open trade records (null PnL)"() {
        given:
        def openTrade = trade(null)  // open trade has no PnL yet
        tradeRepository.findByPairOrderByExecutedAtDesc("BTC-EUR") >> [openTrade]

        when:
        def stats = service.getStats()

        then:
        stats.totalTrades() == 0
    }

    def "getStats calculates 100% win rate for all-winning trades"() {
        given:
        tradeRepository.findByPairOrderByExecutedAtDesc("BTC-EUR") >> [
            trade(BigDecimal.valueOf(100)),
            trade(BigDecimal.valueOf(50)),
            trade(BigDecimal.valueOf(200))
        ]

        when:
        def stats = service.getStats()

        then:
        stats.totalTrades()   == 3
        stats.winningTrades() == 3
        stats.losingTrades()  == 0
        stats.winRate()       == new BigDecimal("100.00")
        stats.totalPnl()      == new BigDecimal("350.00")
        stats.bestTrade()     == new BigDecimal("200.00")
    }

    def "getStats calculates 0% win rate for all-losing trades"() {
        given:
        tradeRepository.findByPairOrderByExecutedAtDesc("BTC-EUR") >> [
            trade(BigDecimal.valueOf(-50)),
            trade(BigDecimal.valueOf(-30))
        ]

        when:
        def stats = service.getStats()

        then:
        stats.totalTrades()   == 2
        stats.winningTrades() == 0
        stats.winRate()       == new BigDecimal("0.00")
        stats.totalPnl()      == new BigDecimal("-80.00")
        stats.worstTrade()    == new BigDecimal("-50.00")
    }

    def "getStats calculates correct win rate and expectancy for mixed trades"() {
        given: "3 wins (+60 total) and 1 loss (-20)"
        tradeRepository.findByPairOrderByExecutedAtDesc("BTC-EUR") >> [
            trade(BigDecimal.valueOf(20)),
            trade(BigDecimal.valueOf(20)),
            trade(BigDecimal.valueOf(20)),
            trade(BigDecimal.valueOf(-20))
        ]

        when:
        def stats = service.getStats()

        then:
        stats.totalTrades()   == 4
        stats.winningTrades() == 3
        stats.losingTrades()  == 1
        stats.winRate()       == new BigDecimal("75.00")
        stats.totalPnl()      == new BigDecimal("40.00")
        stats.averageWin()    == new BigDecimal("20.00")
        stats.averageLoss()   == new BigDecimal("-20.00")
    }

    def "getStats: zero-PnL trades are counted as losses"() {
        given:
        tradeRepository.findByPairOrderByExecutedAtDesc("BTC-EUR") >> [
            trade(BigDecimal.ZERO)
        ]

        when:
        def stats = service.getStats()

        then:
        stats.losingTrades() == 1
        stats.winningTrades() == 0
    }

    // ─── getStatsForStrategy ─────────────────────────────────────────────────

    def "getStatsForStrategy returns empty stats when no trades for strategy"() {
        given:
        tradeRepository.findByPairAndIntervalAndStrategyNameOrderByExecutedAtDesc("BTC-EUR", "15m", StrategyType.MACD) >> []

        when:
        def stats = service.getStatsForStrategy(StrategyType.MACD)

        then:
        stats.totalTrades() == 0
    }

    def "getStatsForStrategy aggregates only the requested strategy's trades"() {
        given:
        tradeRepository.findByPairAndIntervalAndStrategyNameOrderByExecutedAtDesc("BTC-EUR", "15m", StrategyType.EMA_CROSSOVER) >> [
            trade(BigDecimal.valueOf(100)),
            trade(BigDecimal.valueOf(-30))
        ]

        when:
        def stats = service.getStatsForStrategy(StrategyType.EMA_CROSSOVER)

        then:
        stats.totalTrades() == 2
        stats.totalPnl()    == new BigDecimal("70.00")
    }

    // ─── getPnlBreakdown ─────────────────────────────────────────────────────

    def "getPnlBreakdown returns correct periods with scaled values"() {
        given:
        tradeRepository.sumPnlSince(_ as LocalDateTime) >>> [
            BigDecimal.valueOf(50),
            BigDecimal.valueOf(200),
            BigDecimal.valueOf(800),
            BigDecimal.valueOf(3000)
        ]
        tradeRepository.sumNetPnlSince(_ as LocalDateTime) >>> [
            BigDecimal.valueOf(48),
            BigDecimal.valueOf(190),
            BigDecimal.valueOf(780),
            BigDecimal.valueOf(2950)
        ]

        when:
        def breakdown = service.getPnlBreakdown()

        then:
        breakdown.daily()   == new BigDecimal("50.00")
        breakdown.weekly()  == new BigDecimal("200.00")
        breakdown.monthly() == new BigDecimal("800.00")
        breakdown.allTime() == new BigDecimal("3000.00")
    }

    // ─── getPnlBreakdownForStrategy ──────────────────────────────────────────

    def "getPnlBreakdownForStrategy returns strategy-scoped PnL periods"() {
        given:
        tradeRepository.sumPnlClosedSinceAndPairAndIntervalAndStrategy(_ as LocalDateTime, "BTC-EUR", "15m", StrategyType.BOLLINGER) >>> [
            BigDecimal.valueOf(10),
            BigDecimal.valueOf(40),
            BigDecimal.valueOf(150),
            BigDecimal.valueOf(500)
        ]
        tradeRepository.sumNetPnlClosedSinceAndPairAndIntervalAndStrategy(_ as LocalDateTime, "BTC-EUR", "15m", StrategyType.BOLLINGER) >>> [
            BigDecimal.valueOf(9),
            BigDecimal.valueOf(38),
            BigDecimal.valueOf(145),
            BigDecimal.valueOf(475)
        ]

        when:
        def breakdown = service.getPnlBreakdownForStrategy(StrategyType.BOLLINGER)

        then:
        breakdown.daily()   == new BigDecimal("10.00")
        breakdown.weekly()  == new BigDecimal("40.00")
        breakdown.monthly() == new BigDecimal("150.00")
        breakdown.allTime() == new BigDecimal("500.00")
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static Trade trade(BigDecimal pnl) {
        def t = Trade.builder()
                .pair("BTC-EUR")
                .side(OrderSide.BUY)
                .entryPrice(BigDecimal.valueOf(50_000))
                .quantity(new BigDecimal("0.001"))
                .strategyName(StrategyType.EMA_CROSSOVER)
                .build()
        t.pnl = pnl
        t.executedAt = LocalDateTime.now()
        t
    }

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
}
