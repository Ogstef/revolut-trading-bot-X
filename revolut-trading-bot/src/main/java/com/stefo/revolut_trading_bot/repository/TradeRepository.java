package com.stefo.revolut_trading_bot.repository;

import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.entity.Trade;
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
}
