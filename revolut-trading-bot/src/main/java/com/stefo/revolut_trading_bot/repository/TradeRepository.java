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
}
