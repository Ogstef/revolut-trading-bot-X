package com.stefo.revolut_trading_bot.repository;

import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.model.enums.TradingVehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PositionRepository extends JpaRepository<Position, Long> {

    List<Position> findByStatus(OrderStatus status);

    List<Position> findByPairAndStatus(String pair, OrderStatus status);

    long countByStatus(OrderStatus status);

    @Query("SELECT p FROM Position p WHERE p.status = 'OPEN' AND p.pair = :pair")
    List<Position> findOpenPositionsByPair(String pair);

    // ─── Strategy-scoped queries (Phase 7 — multi-strategy) ───────────────────

    List<Position> findByStatusAndStrategyName(OrderStatus status, StrategyType strategyName);

    long countByStatusAndStrategyName(OrderStatus status, StrategyType strategyName);

    // ─── Pair + strategy scoped queries (Phase 8 — multi-pair) ───────────────

    List<Position> findByStatusAndPairAndStrategyName(OrderStatus status, String pair, StrategyType strategyName);

    long countByStatusAndPairAndStrategyName(OrderStatus status, String pair, StrategyType strategyName);

    // ─── Pair + interval + strategy scoped queries (Phase 11 — multi-interval) ──

    List<Position> findByStatusAndPairAndIntervalAndStrategyName(
            OrderStatus status, String pair, String interval, StrategyType strategyName);

    long countByStatusAndPairAndIntervalAndStrategyName(
            OrderStatus status, String pair, String interval, StrategyType strategyName);

    List<Position> findByPairAndIntervalAndStatus(String pair, String interval, OrderStatus status);

    // ─── Vehicle-scoped queries (Phase 13 — leveraged trading) ────────────────

    /** Open positions for a specific (pair, interval, strategy, vehicle) — used by monitorPositions. */
    List<Position> findByStatusAndPairAndIntervalAndStrategyNameAndVehicle(
            OrderStatus status, String pair, String interval, StrategyType strategyName, TradingVehicle vehicle);

    /** Open-position count for a specific (pair, interval, strategy, vehicle) — used by leveraged balance gate. */
    long countByStatusAndPairAndIntervalAndStrategyNameAndVehicle(
            OrderStatus status, String pair, String interval, StrategyType strategyName, TradingVehicle vehicle);

    /** All open positions for a specific vehicle — used to iterate leveraged positions for funding accrual. */
    List<Position> findByStatusAndVehicle(OrderStatus status, TradingVehicle vehicle);

    /**
     * Aggregates open-position counts grouped by (pair, interval, strategy) in a single query —
     * backs the per-cycle SPOT risk snapshot. Filters to SPOT so leveraged positions don't
     * count against spot concurrency caps.
     * Returns rows: [String pair, String interval, StrategyType strategyName, Long count].
     */
    @Query("""
            SELECT p.pair, p.interval, p.strategyName, COUNT(p)
            FROM Position p
            WHERE p.status = :status AND p.vehicle = com.stefo.revolut_trading_bot.model.enums.TradingVehicle.SPOT
            GROUP BY p.pair, p.interval, p.strategyName
            """)
    List<Object[]> countByStatusGroupedByPairIntervalStrategy(@Param("status") OrderStatus status);

    /** Same shape as the SPOT-filtered grouping above, but for a specific leveraged vehicle. */
    @Query("""
            SELECT p.pair, p.interval, p.strategyName, COUNT(p)
            FROM Position p
            WHERE p.status = :status AND p.vehicle = :vehicle
            GROUP BY p.pair, p.interval, p.strategyName
            """)
    List<Object[]> countByStatusGroupedByPairIntervalStrategyAndVehicle(
            @Param("status") OrderStatus status,
            @Param("vehicle") TradingVehicle vehicle);
}
