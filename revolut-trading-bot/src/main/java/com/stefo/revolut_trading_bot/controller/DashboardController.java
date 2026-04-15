package com.stefo.revolut_trading_bot.controller;

import com.stefo.revolut_trading_bot.alert.AlertService;
import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.dto.*;
import com.stefo.revolut_trading_bot.service.FearGreedService;
import com.stefo.revolut_trading_bot.model.entity.SignalLog;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.portfolio.TradingStats;
import com.stefo.revolut_trading_bot.portfolio.TradeService;
import com.stefo.revolut_trading_bot.repository.TradeRepository;
import com.stefo.revolut_trading_bot.scheduler.BotStateService;
import com.stefo.revolut_trading_bot.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monitoring and control REST API for the running bot.
 *
 * All endpoints are read-heavy and cheap — they query DB/state, never call the Revolut API.
 * POST endpoints are control plane — emergency stop, resume, runtime config updates.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DashboardController {

    private final BotStateService botStateService;
    private final TradingConfig tradingConfig;
    private final TradeService tradeService;
    private final TradeRepository tradeRepository;
    private final AlertService alertService;
    private final BotStatusService botStatusService;
    private final PositionService positionService;
    private final ConfigService configService;
    private final StrategyService strategyService;
    private final SignalService signalService;
    private final FearGreedService fearGreedService;

    // ─── Status ───────────────────────────────────────────────────────────────

    /**
     * High-level bot health — running state, mode, circuit breakers, daily PnL.
     * GET /api/status
     */
    @GetMapping("/status")
    public ResponseEntity<BotStatusResponse> status() {
        return ResponseEntity.ok(botStatusService.getStatus());
    }

    // ─── Positions ────────────────────────────────────────────────────────────

    /**
     * All currently open positions enriched with live unrealised PnL.
     * Each position uses the current price of its own pair.
     * GET /api/positions?pair=BTC-EUR (optional pair filter)
     */
    @GetMapping("/positions")
    public ResponseEntity<List<PositionView>> positions(
            @RequestParam(required = false) String pair) {
        return ResponseEntity.ok(positionService.getPositions(pair));
    }

    // ─── Trades ───────────────────────────────────────────────────────────────

    /**
     * Recent closed trades with entry/exit prices and PnL.
     * GET /api/trades?limit=50&pair=BTC-EUR
     * When pair is omitted, returns trades for the primary (first) configured pair.
     */
    @GetMapping("/trades")
    public ResponseEntity<List<Trade>> trades(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String pair) {
        String effectivePair = pair != null ? pair : tradingConfig.primaryPair();
        return ResponseEntity.ok(tradeRepository.findRecentTradesByPair(effectivePair, limit));
    }

    // ─── Stats ────────────────────────────────────────────────────────────────

    /**
     * Aggregate performance statistics: win rate, PnL, expectancy.
     * GET /api/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<TradingStats> stats() {
        return ResponseEntity.ok(tradeService.getStats());
    }

    /**
     * PnL broken down by day / week / month / all-time.
     * GET /api/pnl
     */
    @GetMapping("/pnl")
    public ResponseEntity<PnlBreakdown> pnl() {
        return ResponseEntity.ok(tradeService.getPnlBreakdown());
    }

    // ─── Control plane ────────────────────────────────────────────────────────

    /**
     * Immediately stops the trading loop — no new positions will be opened or closed
     * until POST /api/resume is called. Open positions are NOT automatically closed.
     * POST /api/emergency-stop
     */
    @PostMapping("/emergency-stop")
    public ResponseEntity<String> emergencyStop() {
        log.warn("Emergency stop requested via /api/emergency-stop");
        botStateService.stop();
        alertService.botStopped("REST /api/emergency-stop");
        return ResponseEntity.ok("Bot stopped. Call POST /api/resume to restart trading.");
    }

    /**
     * Resumes the trading loop after an emergency stop.
     * POST /api/resume
     */
    @PostMapping("/resume")
    public ResponseEntity<String> resume() {
        log.info("Resume requested via /api/resume");
        botStateService.resume();
        alertService.botResumed("REST /api/resume");
        return ResponseEntity.ok("Bot resumed. Next cycle will execute on schedule.");
    }

    /**
     * Updates trading/risk parameters at runtime — changes take effect on the next cycle.
     * Only non-null fields in the request body are applied.
     * POST /api/config
     */
    @PostMapping("/config")
    public ResponseEntity<String> updateConfig(@Valid @RequestBody ConfigUpdateRequest req) {
        log.info("Config update requested: {}", req);

        configService.UpdateConfigs(req);

        log.info("Config updated successfully");
        return ResponseEntity.ok("Config updated. Changes take effect on the next trading cycle.");
    }

    // ─── Pairs ────────────────────────────────────────────────────────────────

    /**
     * Lists all configured trading pairs with base/quote asset breakdown.
     * GET /api/pairs
     */
    @GetMapping("/pairs")
    public ResponseEntity<List<Map<String, Object>>> pairs() {
        List<Map<String, Object>> result = tradingConfig.getPairs().stream()
                .map(pair -> {
                    String[] parts = pair.split("-");
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("pair", pair);
                    entry.put("baseAsset", parts.length > 0 ? parts[0] : pair);
                    entry.put("quoteAsset", parts.length > 1 ? parts[1] : "");
                    return entry;
                })
                .toList();
        return ResponseEntity.ok(result);
    }

    // ─── Intervals ──────────────────────────────────────────────────────────

    /**
     * Lists all configured candle intervals with labels and display names.
     * GET /api/intervals
     */
    @GetMapping("/intervals")
    public ResponseEntity<List<Map<String, Object>>> intervals() {
        List<Map<String, Object>> result = tradingConfig.getIntervals().stream()
                .map(mins -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("minutes", mins);
                    entry.put("label", TradingConfig.intervalLabel(mins));
                    entry.put("displayName", TradingConfig.intervalDisplayName(mins));
                    return entry;
                })
                .toList();
        return ResponseEntity.ok(result);
    }

    // ─── Multi-strategy endpoints (Phase 7 + Phase 8 + Phase 11) ─────────────

    /**
     * Lists all registered strategy names and their high-level risk stats.
     * GET /api/strategies?pair=BTC-EUR&interval=15m
     */
    @GetMapping("/strategies")
    public ResponseEntity<List<Map<String, Object>>> strategies(
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String interval) {
        return ResponseEntity.ok(strategyService.getResult(pair, interval));
    }

    /**
     * Open positions for a single strategy enriched with live unrealised PnL.
     * GET /api/strategies/{strategyType}/positions?pair=BTC-EUR&interval=15m
     */
    @GetMapping("/strategies/{strategyType}/positions")
    public ResponseEntity<List<PositionView>> strategyPositions(
            @PathVariable StrategyType strategyType,
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String interval) {
        return ResponseEntity.ok(strategyService.getPositionForStrategy(pair, interval, strategyType));
    }

    /**
     * Closed trades for a single strategy.
     * GET /api/strategies/{strategyType}/trades?pair=BTC-EUR&interval=15m&limit=50
     */
    @GetMapping("/strategies/{strategyType}/trades")
    public ResponseEntity<List<Trade>> strategyTrades(
            @PathVariable StrategyType strategyType,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String interval) {
        String effectivePair     = pair != null ? pair : tradingConfig.primaryPair();
        String effectiveInterval = interval != null ? interval : tradingConfig.primaryInterval();
        return ResponseEntity.ok(
                tradeRepository.findRecentTradesByPairAndIntervalAndStrategy(
                        effectivePair, effectiveInterval, strategyType, limit));
    }

    /**
     * Full trade history for a single (pair, interval, strategy) virtual portfolio,
     * enriched with the entry signal reason, TP/SL targets, opening timestamp,
     * holding duration, and R-multiple.
     *
     * Optional {@code from} / {@code to} ISO-8601 timestamps narrow the result by
     * trade execution time. Returns newest first; no pagination — datasets per
     * (pair, interval, strategy) are bounded.
     *
     * GET /api/strategies/{strategyType}/history?pair=BTC-EUR&interval=15m
     *     &from=2026-01-01T00:00:00&to=2026-04-14T23:59:59
     */
    @GetMapping("/strategies/{strategyType}/history")
    public ResponseEntity<List<TradeHistoryEntry>> strategyHistory(
            @PathVariable StrategyType strategyType,
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String interval,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        String effectivePair     = pair != null ? pair : tradingConfig.primaryPair();
        String effectiveInterval = interval != null ? interval : tradingConfig.primaryInterval();
        List<TradeHistoryEntry> history = tradeRepository
                .findHistoryByPairAndIntervalAndStrategy(
                        effectivePair, effectiveInterval, strategyType, from, to)
                .stream()
                .map(TradeHistoryEntry::from)
                .toList();
        return ResponseEntity.ok(history);
    }

    /**
     * Win rate, PnL, expectancy for a single strategy.
     * GET /api/strategies/{strategyType}/stats?pair=BTC-EUR&interval=15m
     */
    @GetMapping("/strategies/{strategyType}/stats")
    public ResponseEntity<TradingStats> strategyStats(
            @PathVariable StrategyType strategyType,
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String interval) {
        return ResponseEntity.ok(tradeService.getStatsForStrategy(pair, interval, strategyType));
    }

    /**
     * PnL breakdown (daily/weekly/monthly/all-time) for a single strategy.
     * GET /api/strategies/{strategyType}/pnl?pair=BTC-EUR&interval=15m
     */
    @GetMapping("/strategies/{strategyType}/pnl")
    public ResponseEntity<PnlBreakdown> strategyPnl(
            @PathVariable StrategyType strategyType,
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String interval) {
        return ResponseEntity.ok(tradeService.getPnlBreakdownForStrategy(pair, interval, strategyType));
    }

    /**
     * Recent signal logs for a single strategy, newest first.
     * GET /api/strategies/{strategyType}/signals?pair=BTC-EUR&interval=15m&limit=20
     */
    @GetMapping("/strategies/{strategyType}/signals")
    public ResponseEntity<List<SignalLog>> strategySignals(
            @PathVariable StrategyType strategyType,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String interval) {
        return ResponseEntity.ok(
                signalService.getSignalStrategies(pair, interval, strategyType, limit));
    }

    /**
     * BUY/SELL/HOLD signal counts grouped by strategy.
     * GET /api/signals/summary?pair=BTC-EUR&interval=15m
     */
    @GetMapping("/signals/summary")
    public ResponseEntity<List<Map<String, Object>>> signalsSummary(
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String interval) {
        return ResponseEntity.ok(signalService.getSummary(pair, interval));
    }

    /**
     * Most recent signal per (pair, interval, strategy) triple — one row for every
     * configured triple, null-filled when no signal has been logged yet.
     * GET /api/signals/current?pair=BTC-EUR&interval=15m
     */
    @GetMapping("/signals/current")
    public ResponseEntity<List<CurrentSignal>> currentSignals(
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String interval) {
        return ResponseEntity.ok(signalService.getCurrentSignals(pair, interval));
    }

    // ─── Market sentiment ─────────────────────────────────────────────────────

    /**
     * Crypto Fear & Greed Index from alternative.me. Cached 1 hour — indicator only.
     * GET /api/market/fear-greed
     */
    @GetMapping("/market/fear-greed")
    public ResponseEntity<FearGreedResponse> fearGreed() {
        FearGreedResponse result = fearGreedService.get();
        if (result == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(result);
    }
}
