package com.stefo.revolut_trading_bot.repository;

import com.stefo.revolut_trading_bot.model.entity.SignalLog;
import com.stefo.revolut_trading_bot.model.enums.SignalType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SignalLogRepository extends JpaRepository<SignalLog, Long> {

    List<SignalLog> findByPairOrderByCreatedAtDesc(String pair);

    List<SignalLog> findByPairAndSignalTypeAndCreatedAtAfter(
            String pair, SignalType signalType, LocalDateTime after);

    List<SignalLog> findTop10ByPairOrderByCreatedAtDesc(String pair);
}
