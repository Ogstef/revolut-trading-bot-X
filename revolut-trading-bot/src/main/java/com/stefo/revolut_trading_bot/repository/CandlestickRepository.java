package com.stefo.revolut_trading_bot.repository;

import com.stefo.revolut_trading_bot.model.entity.Candlestick;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CandlestickRepository extends JpaRepository<Candlestick, Long> {

    List<Candlestick> findByPairAndIntervalOrderByTimestampAsc(String pair, String interval);

    List<Candlestick> findByPairAndIntervalAndTimestampAfterOrderByTimestampAsc(
            String pair, String interval, LocalDateTime after);

    Optional<Candlestick> findByPairAndIntervalAndTimestamp(
            String pair, String interval, LocalDateTime timestamp);

    Optional<Candlestick> findTopByPairAndIntervalOrderByTimestampDesc(String pair, String interval);
}
