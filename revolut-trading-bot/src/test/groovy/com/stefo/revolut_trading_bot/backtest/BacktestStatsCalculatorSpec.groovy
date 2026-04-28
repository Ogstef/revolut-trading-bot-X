package com.stefo.revolut_trading_bot.backtest

import com.stefo.revolut_trading_bot.model.enums.OrderSide
import spock.lang.Specification

import java.math.BigDecimal
import java.time.LocalDateTime

class BacktestStatsCalculatorSpec extends Specification {

    def "empty trade list returns zero stats"() {
        when:
        def stats = BacktestStatsCalculator.compute([], [], LocalDateTime.now().minusDays(30), LocalDateTime.now())

        then:
        stats.totalTrades() == 0
        stats.winRate() == BigDecimal.ZERO
        stats.netPnl() == BigDecimal.ZERO
        stats.maxDrawdown() == BigDecimal.ZERO
    }

    def "computes basic counts and win rate from a 3-trade list"() {
        given: "2 winners and 1 loser"
        def trades = [
                trade(1, +10.0,  +9.5),     // win after costs
                trade(2,  +5.0,  +4.5),     // win after costs
                trade(3, -10.0, -10.5),     // loss
        ]
        def equity = [point(10000), point(10009.5), point(10014), point(10003.5)]
        def start = LocalDateTime.now().minusDays(30)
        def end = LocalDateTime.now()

        when:
        def stats = BacktestStatsCalculator.compute(trades, equity, start, end)

        then:
        stats.totalTrades() == 3
        stats.winningTrades() == 2
        stats.losingTrades() == 1
        stats.winRate() == new BigDecimal("66.67")
        stats.totalPnl() == new BigDecimal("5.00")     // +10 +5 -10
        stats.netPnl() == new BigDecimal("3.50")       // +9.5 +4.5 -10.5
    }

    def "max consecutive losses tracks the longest losing streak"() {
        given:
        def trades = [
                trade(1, +5.0, +4.5),
                trade(2, -5.0, -5.5),
                trade(3, -3.0, -3.5),
                trade(4, -2.0, -2.5),     // streak of 3
                trade(5, +8.0, +7.5),
                trade(6, -1.0, -1.5),
        ]

        when:
        def stats = BacktestStatsCalculator.compute(trades, [point(10000), point(10004.5)],
                LocalDateTime.now().minusDays(10), LocalDateTime.now())

        then:
        stats.maxConsecutiveLosses() == 3
    }

    def "profit factor is gross-wins over gross-losses"() {
        given:
        def trades = [
                trade(1, +20.0, +18.0),
                trade(2, -10.0, -10.0),
        ]

        when:
        def stats = BacktestStatsCalculator.compute(trades, [point(10000)],
                LocalDateTime.now().minusDays(10), LocalDateTime.now())

        then: "18 / 10 = 1.80"
        stats.profitFactor() == new BigDecimal("1.8000")
    }

    def "max drawdown picks the largest peak-to-trough on the equity curve"() {
        given: "rises to 100, falls to 70 (DD 30), rises to 120, falls to 90 (DD 30 from 120)"
        def equity = [
                point(100),
                point(95),
                point(70),    // DD = 30 from peak 100
                point(110),
                point(120),
                point(90),    // DD = 30 from peak 120 — equal but later
                point(115),
        ]

        when:
        def stats = BacktestStatsCalculator.compute([trade(1, +0, +0)], equity,
                LocalDateTime.now().minusDays(10), LocalDateTime.now())

        then:
        stats.maxDrawdown() == new BigDecimal("30.00")
        stats.maxDrawdownPct() > BigDecimal.ZERO
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static SimulatedTrade trade(int seq, double gross, double net) {
        return new SimulatedTrade(seq, OrderSide.BUY,
                bd(100), bd(100 + gross), bd(1),
                LocalDateTime.now().minusHours(seq + 1),
                LocalDateTime.now().minusHours(seq),
                bd(gross), bd(gross),
                bd(0.05), bd(0.05), bd(0.05), bd(0.05),
                bd(net), bd(net),
                "TEST", "test entry")
    }

    private static EquityPoint point(double v) {
        return new EquityPoint(LocalDateTime.now(), bd(v), BigDecimal.ZERO, BigDecimal.ZERO)
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v)
    }
}
