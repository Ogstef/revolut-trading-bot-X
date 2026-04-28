package com.stefo.revolut_trading_bot.repository;

import com.stefo.revolut_trading_bot.model.entity.BacktestRun;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BacktestRunRepository extends JpaRepository<BacktestRun, UUID> {

    @Query("""
            SELECT b FROM BacktestRun b
             WHERE (:pair      IS NULL OR b.pair      = :pair)
               AND (:strategy  IS NULL OR b.strategy  = :strategy)
               AND (:interval  IS NULL OR b.interval  = :interval)
             ORDER BY b.createdAt DESC
            """)
    List<BacktestRun> findRecentByFilter(
            @Param("pair")     String pair,
            @Param("strategy") StrategyType strategy,
            @Param("interval") String interval,
            Pageable pageable);
}
