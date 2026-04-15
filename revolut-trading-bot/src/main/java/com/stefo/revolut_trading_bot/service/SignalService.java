package com.stefo.revolut_trading_bot.service;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.dto.CurrentSignal;
import com.stefo.revolut_trading_bot.model.entity.SignalLog;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.repository.SignalLogRepository;
import com.stefo.revolut_trading_bot.strategy.SignalEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SignalService {

    private final SignalLogRepository repository;
    private final TradingConfig tradingConfig;
    private final SignalEngine signalEngine;

    public List<Map<String, Object>> getSummary (String pair) {
        String effectivePair = pair != null ? pair : tradingConfig.primaryPair();
        List<Object[]> rows = repository.countSignalsByStrategy(effectivePair);
        return rows.stream()
                .map(row -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("strategy", row[0]);
                    entry.put("signalType", row[1]);
                    entry.put("count", row[2]);
                    return entry;
                })
                .toList();
    }

    public List<Map<String, Object>> getSummary (String pair, String interval) {
        String effectivePair = pair != null ? pair : tradingConfig.primaryPair();
        String effectiveInterval = interval != null ? interval : tradingConfig.primaryInterval();
        List<Object[]> rows = repository.countSignalsByStrategyAndInterval(effectivePair, effectiveInterval);
        return rows.stream()
                .map(row -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("strategy", row[0]);
                    entry.put("signalType", row[1]);
                    entry.put("count", row[2]);
                    return entry;
                })
                .toList();
    }

    public List<SignalLog> getSignalStrategies (String pair, StrategyType strategyType, int limit) {
        String effectivePair = pair != null ? pair : tradingConfig.primaryPair();
        return repository.findRecentByPairAndStrategy(effectivePair, strategyType, limit);
    }

    public List<SignalLog> getSignalStrategies (String pair, String interval, StrategyType strategyType, int limit) {
        String effectivePair = pair != null ? pair : tradingConfig.primaryPair();
        String effectiveInterval = interval != null ? interval : tradingConfig.primaryInterval();
        return repository.findRecentByPairAndIntervalAndStrategy(effectivePair, effectiveInterval, strategyType, limit);
    }

    public List<CurrentSignal> getCurrentSignals(String pair, String interval) {
        List<SignalLog> rows = repository.findLatestPerTriple(pair, interval);

        Map<String, SignalLog> byKey = rows.stream()
                .collect(Collectors.toMap(
                        r -> key(r.getPair(), r.getInterval(), r.getStrategyName()),
                        Function.identity(),
                        (a, b) -> a
                ));

        List<String> pairs = pair != null ? List.of(pair) : tradingConfig.getPairs();
        List<String> intervals = interval != null ? List.of(interval) : tradingConfig.intervalLabels();
        List<StrategyType> strategies = signalEngine.registeredStrategies();

        List<CurrentSignal> out = new ArrayList<>(pairs.size() * intervals.size() * strategies.size());
        for (String p : pairs) {
            for (String itv : intervals) {
                for (StrategyType st : strategies) {
                    SignalLog log = byKey.get(key(p, itv, st));
                    if (log == null) {
                        out.add(new CurrentSignal(p, itv, st.name(), st.getDisplayName(),
                                null, null, null, null, null, null, null, null));
                    } else {
                        out.add(new CurrentSignal(
                                p, itv, st.name(), st.getDisplayName(),
                                log.getSignalType(), log.getConfidence(), log.getReason(),
                                log.getCurrentPrice(), log.getEmaShort(), log.getEmaLong(),
                                log.getRsi(), log.getCreatedAt()));
                    }
                }
            }
        }
        return out;
    }

    private static String key(String pair, String interval, StrategyType st) {
        return pair + "|" + interval + "|" + st.name();
    }
}
