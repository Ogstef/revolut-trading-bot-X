package com.stefo.revolut_trading_bot.strategy;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.market.MarketDataService;
import com.stefo.revolut_trading_bot.model.entity.SignalLog;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.repository.SignalLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates a full signal cycle across all registered strategies and all configured pairs.
 *
 * Phase 8 change: candles are fetched ONCE per pair and then shared across all strategies
 * for that pair. The unit of evaluation is (pair × strategy), producing
 * N_pairs × N_strategies signals per cycle.
 *
 * The class default is readOnly — only the persist methods write.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SignalEngine {

    private final MarketDataService    marketDataService;
    private final SignalLogRepository  signalLogRepository;
    private final TradingConfig        tradingConfig;

    // Spring collects every @Component that implements TradingStrategy into this list.
    private final List<TradingStrategy> strategies;

    /**
     * Main multi-pair entry point for the trading loop.
     *
     * Fetches candles ONCE per configured pair, then evaluates all strategies on each pair.
     * Persists every signal.
     *
     * Returns all (N_pairs × N_strategies) signals — the trading loop groups them by pair.
     */
    @Transactional
    public List<Signal> evaluateAllPairsAndPersist() {
        List<String> pairs = tradingConfig.getPairs();
        List<Signal> allSignals = new ArrayList<>();

        for (String pair : pairs) {
            BarSeries series = marketDataService.fetchBarSeriesForPair(pair);

            for (TradingStrategy strategy : strategies) {
                Signal signal = strategy.evaluate(series, pair);
                persist(signal);
                log.info("[{}][{}] {} confidence={} — {}",
                        pair, strategy.strategyType(), signal.type(), signal.confidence(), signal.reason());
                allSignals.add(signal);
            }
        }

        return allSignals;
    }

    /**
     * Backward-compatible single-pair evaluation — fetches candles for all configured
     * pairs but returns only the primary strategy's signal for the primary pair.
     * Used by legacy callers.
     */
    @Transactional
    public Signal evaluateAndPersist() {
        List<Signal> all = evaluateAllPairsAndPersist();
        return primarySignal(all);
    }

    /**
     * Evaluates all strategies on the cached BarSeries for all pairs — no API call, no DB write.
     * Useful for test endpoints.
     */
    public List<Signal> evaluateAllFromCache() {
        List<Signal> allSignals = new ArrayList<>();
        for (String pair : tradingConfig.getPairs()) {
            BarSeries series = marketDataService.getBarSeriesForPair(pair);
            for (TradingStrategy strategy : strategies) {
                allSignals.add(strategy.evaluate(series, pair));
            }
        }
        return allSignals;
    }

    /**
     * Evaluates using cached BarSeries for the primary pair.
     * Returns the primary strategy's signal. Backward-compatible test helper.
     */
    public Signal evaluateFromCache() {
        return primarySignal(evaluateAllFromCache());
    }

    /**
     * Returns the N most recent signal logs for a specific pair (all strategies).
     */
    public List<SignalLog> recentSignalsForPair(String pair, int limit) {
        return signalLogRepository.findByPairOrderByCreatedAtDesc(pair)
                .stream()
                .limit(limit)
                .toList();
    }

    /**
     * Returns the N most recent signal logs for the primary pair (all strategies).
     * Backward-compatible helper.
     */
    public List<SignalLog> recentSignals(int limit) {
        return recentSignalsForPair(tradingConfig.primaryPair(), limit);
    }

    /**
     * Returns recent signal logs filtered to a single (pair, strategy) combination.
     */
    public List<SignalLog> recentSignalsForPairAndStrategy(String pair, StrategyType strategyType, int limit) {
        return signalLogRepository
                .findRecentByPairAndStrategy(pair, strategyType, limit);
    }

    /**
     * Returns recent signal logs filtered to a single strategy on the primary pair.
     * Backward-compatible helper.
     */
    public List<SignalLog> recentSignalsForStrategy(StrategyType strategyType, int limit) {
        return recentSignalsForPairAndStrategy(tradingConfig.primaryPair(), strategyType, limit);
    }

    /** Registered strategy types — useful for dashboard listing. */
    public List<StrategyType> registeredStrategies() {
        return strategies.stream().map(TradingStrategy::strategyType).toList();
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private Signal primarySignal(List<Signal> signals) {
        String primaryPair           = tradingConfig.primaryPair();
        StrategyType primaryStrategy = tradingConfig.getPrimaryStrategy();
        return signals.stream()
                .filter(s -> s.pair().equals(primaryPair) && s.strategyType() == primaryStrategy)
                .findFirst()
                .orElseGet(() -> {
                    log.warn("Primary signal [{}/{}] not found — falling back to first",
                            primaryPair, primaryStrategy);
                    return signals.get(0);
                });
    }

    @Transactional
    protected void persist(Signal signal) {
        SignalLog entry = SignalLog.builder()
                .pair(signal.pair())
                .strategyName(signal.strategyType())
                .signalType(signal.type())
                .confidence(signal.confidence())
                .reason(signal.reason())
                .emaShort(signal.emaShort())
                .emaLong(signal.emaLong())
                .rsi(signal.rsi())
                .currentPrice(signal.currentPrice())
                .build();
        signalLogRepository.save(entry);
    }
}
