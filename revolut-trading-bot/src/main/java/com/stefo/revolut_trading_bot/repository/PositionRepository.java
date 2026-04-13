package com.stefo.revolut_trading_bot.repository;

import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
}
