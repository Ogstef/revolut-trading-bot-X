package com.stefo.revolut_trading_bot.repository;

import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.model.enums.TradingMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TradeRepository extends JpaRepository<Trade, Long> {

    List<Trade> findByPairOrderByExecutedAtDesc(String pair);

    List<Trade> findByTradingMode(TradingMode tradingMode);

    List<Trade> findByExecutedAtAfter(LocalDateTime since);

    @Query("SELECT COALESCE(SUM(t.pnl), 0) FROM Trade t WHERE t.executedAt >= :since")
    BigDecimal sumPnlSince(LocalDateTime since);

    @Query("SELECT COALESCE(SUM(COALESCE(t.netPnl, t.pnl)), 0) FROM Trade t WHERE t.executedAt >= :since")
    BigDecimal sumNetPnlSince(LocalDateTime since);

    @Query("""
            SELECT t FROM Trade t WHERE t.pair = :pair
            ORDER BY t.executedAt DESC
            LIMIT :limit
            """)
    List<Trade> findRecentTradesByPair(String pair, int limit);

    // Used by PaperTradingService to locate the open trade record when closing a position
    @Query("SELECT t FROM Trade t WHERE t.position = :position AND t.exitPrice IS NULL")
    Optional<Trade> findOpenTradeByPosition(@Param("position") Position position);

    // ─── Strategy-scoped queries (Phase 7 — multi-strategy) ───────────────────

    @Query("SELECT COALESCE(SUM(t.pnl), 0) FROM Trade t WHERE t.executedAt >= :since AND t.strategyName = :strategyName")
    BigDecimal sumPnlSinceAndStrategyName(@Param("since") LocalDateTime since,
                                          @Param("strategyName") StrategyType strategyName);

    // ─── Pair + strategy scoped queries (Phase 8 — multi-pair) ──────────────

    /** Daily PnL scoped to a specific (pair, strategy) — used by per-pair circuit breakers. */
    @Query("SELECT COALESCE(SUM(t.pnl), 0) FROM Trade t WHERE t.executedAt >= :since AND t.pair = :pair AND t.strategyName = :strategyName")
    BigDecimal sumPnlSinceAndPairAndStrategy(@Param("since") LocalDateTime since,
                                             @Param("pair") String pair,
                                             @Param("strategyName") StrategyType strategyName);

    @Query("""
            SELECT t FROM Trade t WHERE t.pair = :pair AND t.strategyName = :strategyName
            ORDER BY t.executedAt DESC
            LIMIT :limit
            """)
    List<Trade> findRecentTradesByPairAndStrategy(@Param("pair") String pair,
                                                  @Param("strategyName") StrategyType strategyName,
                                                  @Param("limit") int limit);

    List<Trade> findByStrategyNameOrderByExecutedAtDesc(StrategyType strategyName);

    // ─── Pair + interval + strategy scoped queries (Phase 11 — multi-interval) ──

    @Query("SELECT COALESCE(SUM(t.pnl), 0) FROM Trade t WHERE t.executedAt >= :since AND t.pair = :pair AND t.interval = :interval AND t.strategyName = :strategyName")
    BigDecimal sumPnlSinceAndPairAndIntervalAndStrategy(@Param("since") LocalDateTime since,
                                                         @Param("pair") String pair,
                                                         @Param("interval") String interval,
                                                         @Param("strategyName") StrategyType strategyName);

    @Query("""
            SELECT t FROM Trade t WHERE t.pair = :pair AND t.interval = :interval AND t.strategyName = :strategyName
            ORDER BY t.executedAt DESC
            LIMIT :limit
            """)
    List<Trade> findRecentTradesByPairAndIntervalAndStrategy(@Param("pair") String pair,
                                                              @Param("interval") String interval,
                                                              @Param("strategyName") StrategyType strategyName,
                                                              @Param("limit") int limit);

    List<Trade> findByPairAndIntervalAndStrategyNameOrderByExecutedAtDesc(
            String pair, String interval, StrategyType strategyName);

    List<Trade> findByPairAndIntervalOrderByExecutedAtDesc(String pair, String interval);

    /**
     * Full trade history for a (pair, interval, strategy) virtual portfolio with optional
     * date-range bounds. Fetch-joins the parent Position so the response can be enriched
     * with signalReason / TP / SL / openedAt without N+1 lazy loads.
     *
     * Either or both of {@code from} / {@code to} may be null (no lower / upper bound).
     * The {@code CAST(:param AS LocalDateTime)} wrappers are required so Postgres can
     * determine the JDBC parameter type when the value is null — without them the driver
     * raises {@code ERROR: could not determine data type of parameter $N} (SQLState 42P18).
     */
    @Query("""
            SELECT t FROM Trade t
            LEFT JOIN FETCH t.position p
            WHERE t.pair = :pair
              AND t.interval = :interval
              AND t.strategyName = :strategyName
              AND (CAST(:from AS LocalDateTime) IS NULL OR t.executedAt >= :from)
              AND (CAST(:to   AS LocalDateTime) IS NULL OR t.executedAt <= :to)
            ORDER BY t.executedAt DESC
            """)
    List<Trade> findHistoryByPairAndIntervalAndStrategy(@Param("pair") String pair,
                                                        @Param("interval") String interval,
                                                        @Param("strategyName") StrategyType strategyName,
                                                        @Param("from") LocalDateTime from,
                                                        @Param("to") LocalDateTime to);

    // ─── Aggregate queries — back the per-cycle risk snapshot (§3.3) ─────────

    /**
     * Sums PnL since a cutoff, grouped by (pair, interval, strategy) in a single query.
     * Returns rows: [String pair, String interval, StrategyType strategyName, BigDecimal sum].
     */
    @Query("""
            SELECT t.pair, t.interval, t.strategyName, COALESCE(SUM(t.pnl), 0)
            FROM Trade t
            WHERE t.executedAt >= :since
            GROUP BY t.pair, t.interval, t.strategyName
            """)
    List<Object[]> sumPnlSinceGroupedByPairIntervalStrategy(@Param("since") LocalDateTime since);

    /**
     * Trades executed after a cutoff, newest first. Used to compute consecutive-loss streaks
     * across all (pair, interval, strategy) keys in one query; the cycle builder groups in-memory.
     */
    @Query("""
            SELECT t FROM Trade t
            WHERE t.executedAt >= :since
            ORDER BY t.executedAt DESC
            """)
    List<Trade> findByExecutedAtAfterOrderByExecutedAtDesc(@Param("since") LocalDateTime since);

    /**
     * Returns all trades closed since the given timestamp with a finalized PnL,
     * ordered by PnL descending (winners first, losers last).
     * Used by the daily summary scheduler.
     */
    @Query("SELECT t FROM Trade t WHERE t.closedAt >= :since AND t.pnl IS NOT NULL ORDER BY t.pnl DESC")
    List<Trade> findClosedTradesSince(@Param("since") LocalDateTime since);

    /**
     * Aggregates closed-trade stats grouped by (pair, interval, strategy) in a single query —
     * backs the Grand Leaderboard endpoint (Tab 2). Returns one row per triple that has at
     * least one closed trade.
     *
     * Row layout:
     *   [0] String        pair
     *   [1] String        interval
     *   [2] StrategyType  strategyName
     *   [3] Long          totalTrades
     *   [4] Long          winningTrades
     *   [5] Long          losingTrades
     *   [6] BigDecimal    totalPnl
     *   [7] BigDecimal    averageWin    (avg of pnl > 0, 0 when no winners)
     *   [8] BigDecimal    averageLoss   (avg of pnl <= 0, 0 when no losers)
     *   [9] BigDecimal    bestTrade
     *  [10] BigDecimal    worstTrade
     */
    @Query("""
            SELECT t.pair,
                   t.interval,
                   t.strategyName,
                   COUNT(t),
                   SUM(CASE WHEN t.pnl > 0 THEN 1 ELSE 0 END),
                   SUM(CASE WHEN t.pnl <= 0 THEN 1 ELSE 0 END),
                   COALESCE(SUM(t.pnl), 0),
                   COALESCE(AVG(CASE WHEN t.pnl > 0  THEN t.pnl END), 0),
                   COALESCE(AVG(CASE WHEN t.pnl <= 0 THEN t.pnl END), 0),
                   COALESCE(MAX(t.pnl), 0),
                   COALESCE(MIN(t.pnl), 0),
                   COALESCE(SUM(t.netPnl), 0),
                   COALESCE(SUM(t.entryFee + t.exitFee + t.entrySlippage + t.exitSlippage), 0),
                   COALESCE(AVG(CASE WHEN t.netPnl > 0  THEN t.netPnl END), 0),
                   COALESCE(AVG(CASE WHEN t.netPnl <= 0 THEN t.netPnl END), 0)
            FROM Trade t
            WHERE t.closedAt IS NOT NULL AND t.pnl IS NOT NULL
            GROUP BY t.pair, t.interval, t.strategyName
            """)
    List<Object[]> aggregateStatsByTriple();

    @Query("SELECT COALESCE(SUM(COALESCE(t.netPnl, t.pnl)), 0) FROM Trade t WHERE t.executedAt >= :since AND t.pair = :pair AND t.interval = :interval AND t.strategyName = :strategyName")
    BigDecimal sumNetPnlSinceAndPairAndIntervalAndStrategy(@Param("since") LocalDateTime since,
                                                            @Param("pair") String pair,
                                                            @Param("interval") String interval,
                                                            @Param("strategyName") StrategyType strategyName);
}
