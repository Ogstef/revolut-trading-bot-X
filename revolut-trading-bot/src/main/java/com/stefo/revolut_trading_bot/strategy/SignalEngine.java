package com.stefo.revolut_trading_bot.strategy;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.market.MarketDataService;
import com.stefo.revolut_trading_bot.model.entity.SignalLog;
import com.stefo.revolut_trading_bot.repository.SignalLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.ta4j.core.BarSeries;

import java.util.List;

/**
 * Orchestrates a full signal cycle:
 *   1. Fetch the latest candles from the API (or use cache)
 *   2. Run EmaCrossoverStrategy against the BarSeries
 *   3. Persist the result to signal_logs
 *
 * The class default is readOnly — only evaluateAndPersist() writes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SignalEngine {

    private final MarketDataService marketDataService;
    private final SignalLogRepository signalLogRepository;
    private final TradingConfig tradingConfig;
    private final TradingStrategy tradingStrategy;   // EmaCrossoverStrategy

    /**
     * Pulls fresh candles from Revolut, runs the strategy, persists the signal, returns it.
     * This is the entry point for the trading loop.
     */
    @Transactional
    public Signal evaluateAndPersist() {
        BarSeries series = marketDataService.fetchAndBuildBarSeries();
        Signal signal = tradingStrategy.evaluate(series);
        persist(signal);
        log.info("Signal persisted: {} confidence={} — {}",
                signal.type(), signal.confidence(), signal.reason());
        return signal;
    }

    /**
     * Evaluates using the in-memory BarSeries — no API call.
     * Useful for test endpoints and fast re-evaluation.
     * Does NOT persist.
     */
    public Signal evaluateFromCache() {
        BarSeries series = marketDataService.getBarSeries();
        Signal signal = tradingStrategy.evaluate(series);
        log.info("Signal (cached): {} — {}", signal.type(), signal.reason());
        return signal;
    }

    /**
     * Returns the N most recent signal logs for the configured trading pair.
     */
    public List<SignalLog> recentSignals(int limit) {
        return signalLogRepository
                .findByPairOrderByCreatedAtDesc(tradingConfig.getPair())
                .stream()
                .limit(limit)
                .toList();
    }

    private void persist(Signal signal) {
        SignalLog entry = SignalLog.builder()
                .pair(tradingConfig.getPair())
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
