package com.stefo.revolut_trading_bot.repository;

import com.stefo.revolut_trading_bot.model.entity.SignalLog;
import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SignalLogRepository extends JpaRepository<SignalLog, Long> {

    List<SignalLog> findByPairOrderByCreatedAtDesc(String pair);

    List<SignalLog> findByPairAndSignalTypeAndCreatedAtAfter(
            String pair, SignalType signalType, LocalDateTime after);

    List<SignalLog> findTop10ByPairOrderByCreatedAtDesc(String pair);

    // ─── Strategy-scoped queries (Phase 7 — multi-strategy) ───────────────────

    List<SignalLog> findByPairAndStrategyNameOrderByCreatedAtDesc(String pair, StrategyType strategyName);

    @Query("""
            SELECT s FROM SignalLog s
            WHERE s.pair = :pair AND s.strategyName = :strategyName
            ORDER BY s.createdAt DESC
            LIMIT :limit
            """)
    List<SignalLog> findRecentByPairAndStrategy(@Param("pair") String pair,
                                                @Param("strategyName") StrategyType strategyName,
                                                @Param("limit") int limit);

    /** Counts BUY/SELL/HOLD signals grouped by strategy — used for the /api/signals/summary endpoint */
    @org.springframework.data.jpa.repository.Query(
            "SELECT s.strategyName, s.signalType, COUNT(s) FROM SignalLog s WHERE s.pair = :pair GROUP BY s.strategyName, s.signalType")
    List<Object[]> countSignalsByStrategy(@org.springframework.data.repository.query.Param("pair") String pair);

    // ─── Pair + interval + strategy scoped queries (Phase 11 — multi-interval) ──

    @Query("""
            SELECT s FROM SignalLog s
            WHERE s.pair = :pair AND s.interval = :interval AND s.strategyName = :strategyName
            ORDER BY s.createdAt DESC
            LIMIT :limit
            """)
    List<SignalLog> findRecentByPairAndIntervalAndStrategy(@Param("pair") String pair,
                                                            @Param("interval") String interval,
                                                            @Param("strategyName") StrategyType strategyName,
                                                            @Param("limit") int limit);

    @Query("""
            SELECT s FROM SignalLog s
            WHERE s.pair = :pair AND s.interval = :interval
            ORDER BY s.createdAt DESC
            LIMIT :limit
            """)
    List<SignalLog> findRecentByPairAndInterval(@Param("pair") String pair,
                                                @Param("interval") String interval,
                                                @Param("limit") int limit);

    @Query("SELECT s.strategyName, s.signalType, COUNT(s) FROM SignalLog s WHERE s.pair = :pair AND s.interval = :interval GROUP BY s.strategyName, s.signalType")
    List<Object[]> countSignalsByStrategyAndInterval(@Param("pair") String pair,
                                                      @Param("interval") String interval);
}
