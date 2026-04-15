package com.stefo.revolut_trading_bot.repository;

import com.stefo.revolut_trading_bot.model.entity.BotEvent;
import com.stefo.revolut_trading_bot.model.enums.BotEventType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface BotEventRepository extends JpaRepository<BotEvent, Long> {

    List<BotEvent> findAllByOrderByCreatedAtDesc(Pageable page);

    List<BotEvent> findByTypeInOrderByCreatedAtDesc(Collection<BotEventType> types, Pageable page);
}
