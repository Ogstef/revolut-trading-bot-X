package com.stefo.revolut_trading_bot.service;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.market.MarketDataService;
import com.stefo.revolut_trading_bot.model.dto.PositionView;
import com.stefo.revolut_trading_bot.model.dto.TradeHistoryEntry;
import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.repository.TradeRepository;
import com.stefo.revolut_trading_bot.risk.RiskManager;
import com.stefo.revolut_trading_bot.strategy.SignalEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.stefo.revolut_trading_bot.utils.PositionUtils.toPositionView;

@Service
@RequiredArgsConstructor
public class StrategyService {

    private final TradingConfig tradingConfig;
    private final SignalEngine signalEngine;
    private final RiskManager riskManager;
    private final MarketDataService marketDataService;
    private final PositionService positionService;
    private final TradeRepository tradeRepository;


    public List<Map<String, Object>> getResult(String pair) {
        return getResult(pair, null);
    }

    public List<Map<String, Object>> getResult(String pair, String interval) {

        String effectivePair = pair != null ? pair : tradingConfig.primaryPair();
        String effectiveInterval = interval != null ? interval : tradingConfig.primaryInterval();
        List<StrategyType> registered = signalEngine.registeredStrategies();
        BigDecimal fallbackBalance = tradingConfig.getPaperBalance();

        return registered.stream()
                .map(strategyType -> {
                    Map<String, Map<StrategyType, BigDecimal>> allBalances = tradingConfig.getStrategyBalances();
                    BigDecimal stratBalance = (allBalances != null && allBalances.containsKey(effectivePair)
                            && allBalances.get(effectivePair).containsKey(strategyType))
                            ? allBalances.get(effectivePair).get(strategyType)
                            : fallbackBalance;
                    RiskManager.RiskStatus risk = riskManager.currentStatusForStrategy(stratBalance, effectivePair, effectiveInterval, strategyType);
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("pair", effectivePair);
                    entry.put("interval", effectiveInterval);
                    entry.put("name", strategyType.name());
                    entry.put("displayName", strategyType.getDisplayName());
                    entry.put("openPositions", risk.openPositions());
                    entry.put("dailyPnl", risk.dailyPnl());
                    entry.put("consecutiveLosses", risk.consecutiveLosses());
                    entry.put("circuitBreakerActive", risk.anyCircuitBreakerTripped());
                    return entry;
                })
                .toList();
    }

    public List<PositionView> getPositionForStrategy(String pair, StrategyType strategyType) {
        return getPositionForStrategy(pair, null, strategyType);
    }

    public List<PositionView> getPositionForStrategy(String pair, String interval, StrategyType strategyType) {
        String effectivePair = pair != null ? pair : tradingConfig.primaryPair();
        String effectiveInterval = interval != null ? interval : tradingConfig.primaryInterval();
        BigDecimal currentPrice = marketDataService.getCurrentPriceForPair(effectivePair);
        List<Position> open = positionService.getOpenPositions(effectivePair, effectiveInterval, strategyType);
        return open.stream().map(p -> toPositionView(p, currentPrice)).toList();
    }

    public List<TradeHistoryEntry> getTradeHistory(String pair, String interval, StrategyType strategyType,
                                                   LocalDateTime from, LocalDateTime to) {
        String effectivePair     = pair != null ? pair : tradingConfig.primaryPair();
        String effectiveInterval = interval != null ? interval : tradingConfig.primaryInterval();
        var trades = tradeRepository.findHistoryByPairAndIntervalAndStrategy(
                effectivePair, effectiveInterval, strategyType, from, to);
        return trades.stream().map(TradeHistoryEntry::from).toList();
    }
}
