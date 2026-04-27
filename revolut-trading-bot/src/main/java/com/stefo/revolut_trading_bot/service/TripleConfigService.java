package com.stefo.revolut_trading_bot.service;

import com.stefo.revolut_trading_bot.model.entity.DisabledTriple;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.repository.DisabledTripleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the in-memory set of disabled (pair, strategy, interval) triples and
 * keeps it synchronized with the trading.disabled_triples table.
 *
 * Reads happen 240 times per trading cycle (once per triple), so the lookup
 * MUST be lock-free and allocation-free — backed by ConcurrentHashMap.newKeySet().
 *
 * Mutations are infrequent (UI clicks). Each mutation: writes the DB, updates
 * the cache, audits via BotEventService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripleConfigService {

    public record TripleKey(String pair, String interval, StrategyType strategy) {}

    private final DisabledTripleRepository repository;
    private final BotEventService botEventService;

    private final Set<TripleKey> disabled = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void load() {
        List<DisabledTriple> rows = repository.findAll();
        rows.forEach(r -> disabled.add(toKey(r)));
        log.info("TripleConfigService loaded — {} disabled triples in cache", disabled.size());
    }

    public boolean isEnabled(String pair, String interval, StrategyType strategy) {
        return !disabled.contains(new TripleKey(pair, interval, strategy));
    }

    @Transactional
    public DisabledTriple disable(String pair, String interval, StrategyType strategy, String reason) {
        DisabledTriple row = DisabledTriple.builder()
                .pair(pair)
                .interval(interval)
                .strategyName(strategy)
                .disabledAt(LocalDateTime.now())
                .reason(reason)
                .build();
        DisabledTriple saved = repository.save(row);
        disabled.add(new TripleKey(pair, interval, strategy));
        botEventService.recordTripleToggled(pair, interval, strategy, false, reason);
        log.info("Triple disabled: {} {} {} — reason: {}", pair, strategy, interval, reason);
        return saved;
    }

    @Transactional
    public void enable(String pair, String interval, StrategyType strategy) {
        repository.deleteByPairAndStrategyNameAndInterval(pair, strategy, interval);
        disabled.remove(new TripleKey(pair, interval, strategy));
        botEventService.recordTripleToggled(pair, interval, strategy, true, null);
        log.info("Triple enabled: {} {} {}", pair, strategy, interval);
    }

    public List<DisabledTriple> listDisabled() {
        return repository.findAllByOrderByDisabledAtDesc();
    }

    private static TripleKey toKey(DisabledTriple row) {
        return new TripleKey(row.getPair(), row.getInterval(), row.getStrategyName());
    }
}
