package com.stefo.revolut_trading_bot.repository;

import com.stefo.revolut_trading_bot.model.entity.DisabledTriple;
import com.stefo.revolut_trading_bot.model.entity.DisabledTripleId;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DisabledTripleRepository extends JpaRepository<DisabledTriple, DisabledTripleId> {

    List<DisabledTriple> findAllByOrderByDisabledAtDesc();

    void deleteByPairAndStrategyNameAndInterval(String pair, StrategyType strategyName, String interval);

    boolean existsByPairAndStrategyNameAndInterval(String pair, StrategyType strategyName, String interval);
}
