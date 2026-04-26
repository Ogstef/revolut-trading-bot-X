package com.stefo.revolut_trading_bot.service;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.dto.TripleStats;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.repository.PositionRepository;
import com.stefo.revolut_trading_bot.repository.TradeRepository;
import com.stefo.revolut_trading_bot.risk.RiskManager;
import com.stefo.revolut_trading_bot.strategy.SignalEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatsAggregationService {

    private final TradeRepository tradeRepository;
    private final PositionRepository positionRepository;
    private final TradingConfig tradingConfig;
    private final SignalEngine signalEngine;
    private final RiskManager riskManager;

    public List<TripleStats> getAllTripleStats() {
        Map<String, TradeAgg> tradeAggByKey = new HashMap<>();
        for (Object[] row : tradeRepository.aggregateStatsByTriple()) {
            String pair = (String) row[0];
            String interval = (String) row[1];
            StrategyType strategy = (StrategyType) row[2];
            tradeAggByKey.put(key(pair, interval, strategy), new TradeAgg(
                    ((Number) row[3]).intValue(),
                    ((Number) row[4]).intValue(),
                    ((Number) row[5]).intValue(),
                    toBd(row[6]),
                    toBd(row[7]),
                    toBd(row[8]),
                    toBd(row[9]),
                    toBd(row[10]),
                    toBd(row[11]),
                    toBd(row[12]),
                    toBd(row[13]),
                    toBd(row[14])
            ));
        }

        Map<String, Long> openByKey = new HashMap<>();
        for (Object[] row : positionRepository.countByStatusGroupedByPairIntervalStrategy(OrderStatus.OPEN)) {
            String pair = (String) row[0];
            String interval = (String) row[1];
            StrategyType strategy = (StrategyType) row[2];
            openByKey.put(key(pair, interval, strategy), ((Number) row[3]).longValue());
        }

        List<String> pairs = tradingConfig.getPairs();
        List<String> intervals = tradingConfig.intervalLabels();
        List<StrategyType> strategies = signalEngine.registeredStrategies();
        Map<String, Map<StrategyType, BigDecimal>> allBalances = tradingConfig.getStrategyBalances();
        BigDecimal fallbackBalance = tradingConfig.getPaperBalance();

        List<TripleStats> result = new ArrayList<>(pairs.size() * intervals.size() * strategies.size());
        for (String pair : pairs) {
            for (String interval : intervals) {
                for (StrategyType strategy : strategies) {
                    String k = key(pair, interval, strategy);
                    TradeAgg agg = tradeAggByKey.getOrDefault(k, TradeAgg.empty());
                    int openPositions = openByKey.getOrDefault(k, 0L).intValue();

                    BigDecimal stratBalance = (allBalances != null
                            && allBalances.containsKey(pair)
                            && allBalances.get(pair).containsKey(strategy))
                            ? allBalances.get(pair).get(strategy)
                            : fallbackBalance;
                    boolean cbActive = riskManager
                            .currentStatusForStrategy(stratBalance, pair, interval, strategy)
                            .anyCircuitBreakerTripped();

                    BigDecimal winRate;
                    BigDecimal expectancy;
                    BigDecimal netExpectancy;
                    BigDecimal feeDragPct;
                    if (agg.totalTrades == 0) {
                        winRate = BigDecimal.ZERO;
                        expectancy = BigDecimal.ZERO;
                        netExpectancy = BigDecimal.ZERO;
                        feeDragPct = BigDecimal.ZERO;
                    } else {
                        winRate = BigDecimal.valueOf(agg.winningTrades)
                                .divide(BigDecimal.valueOf(agg.totalTrades), 8, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                                .setScale(2, RoundingMode.HALF_UP);
                        BigDecimal winRateFrac = winRate.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
                        BigDecimal lossRateFrac = BigDecimal.ONE.subtract(winRateFrac);
                        expectancy = winRateFrac.multiply(agg.averageWin)
                                .add(lossRateFrac.multiply(agg.averageLoss))
                                .setScale(4, RoundingMode.HALF_UP);
                        netExpectancy = winRateFrac.multiply(agg.averageNetWin)
                                .add(lossRateFrac.multiply(agg.averageNetLoss))
                                .setScale(4, RoundingMode.HALF_UP);
                        feeDragPct = agg.totalPnl.abs().compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                                : agg.totalCosts.divide(agg.totalPnl.abs(), 4, RoundingMode.HALF_UP)
                                               .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
                    }

                    result.add(new TripleStats(
                            pair,
                            interval,
                            strategy.name(),
                            strategy.getDisplayName(),
                            agg.totalTrades,
                            agg.winningTrades,
                            agg.losingTrades,
                            winRate,
                            agg.totalPnl.setScale(2, RoundingMode.HALF_UP),
                            agg.averageWin.setScale(2, RoundingMode.HALF_UP),
                            agg.averageLoss.setScale(2, RoundingMode.HALF_UP),
                            agg.bestTrade.setScale(2, RoundingMode.HALF_UP),
                            agg.worstTrade.setScale(2, RoundingMode.HALF_UP),
                            expectancy,
                            openPositions,
                            cbActive,
                            agg.totalNetPnl.setScale(2, RoundingMode.HALF_UP),
                            agg.totalCosts.setScale(2, RoundingMode.HALF_UP),
                            feeDragPct,
                            netExpectancy
                    ));
                }
            }
        }
        return result;
    }

    private static String key(String pair, String interval, StrategyType strategy) {
        return pair + "|" + interval + "|" + strategy.name();
    }

    private static BigDecimal toBd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        throw new IllegalStateException("Unexpected numeric aggregate type: " + o.getClass());
    }

    private record TradeAgg(
            int totalTrades,
            int winningTrades,
            int losingTrades,
            BigDecimal totalPnl,
            BigDecimal averageWin,
            BigDecimal averageLoss,
            BigDecimal bestTrade,
            BigDecimal worstTrade,
            BigDecimal totalNetPnl,
            BigDecimal totalCosts,
            BigDecimal averageNetWin,
            BigDecimal averageNetLoss
    ) {
        static TradeAgg empty() {
            return new TradeAgg(0, 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }
}
