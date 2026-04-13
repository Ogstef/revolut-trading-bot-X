package com.stefo.revolut_trading_bot.service;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.entity.SignalLog;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.repository.SignalLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SignalService {

    private final SignalLogRepository repository;
    private final TradingConfig tradingConfig;

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

    public List<SignalLog> getSignalStrategies (String pair, StrategyType strategyType, int limit) {
        String effectivePair = pair != null ? pair : tradingConfig.primaryPair();
        return repository.findRecentByPairAndStrategy(effectivePair, strategyType, limit);
    }
}
