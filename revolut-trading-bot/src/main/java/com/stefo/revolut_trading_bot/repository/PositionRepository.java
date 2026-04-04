package com.stefo.revolut_trading_bot.repository;

import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
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
}
