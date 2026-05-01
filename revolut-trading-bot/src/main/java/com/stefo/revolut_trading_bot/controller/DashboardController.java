package com.stefo.revolut_trading_bot.controller;

import com.stefo.revolut_trading_bot.alert.AlertService;
import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.dto.*;
import com.stefo.revolut_trading_bot.service.FearGreedService;
import com.stefo.revolut_trading_bot.model.entity.BotEvent;
import com.stefo.revolut_trading_bot.model.entity.SignalLog;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import com.stefo.revolut_trading_bot.model.enums.BotEventType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.portfolio.TradingStats;
import com.stefo.revolut_trading_bot.portfolio.TradeService;
import com.stefo.revolut_trading_bot.repository.CandlestickRepository;
import com.stefo.revolut_trading_bot.repository.TradeRepository;
import com.stefo.revolut_trading_bot.scheduler.BotStateService;
import com.stefo.revolut_trading_bot.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final StatsAggregationService statsAggregationService;
    private final BotEventService botEventService;
    private final CandlestickRepository candlestickRepository;
    private final SentimentService sentimentService;
    private final TripleConfigService tripleConfigService;
    private final TodaySummaryService todaySummaryService;

    // ─── Status ───────────────────────────────────────────────────────────────

    @GetMapping("/status")
    public ResponseEntity<BotStatusResponse> status() {
        return ResponseEntity.ok(botStatusService.getStatus());
    }

    // ─── Positions ────────────────────────────────────────────────────────────

    @GetMapping("/positions")
    public ResponseEntity<List<PositionView>> positions(
            @RequestParam(required = false) String pair) {
        return ResponseEntity.ok(positionService.getPositions(pair));
    }

    @GetMapping("/positions/live")
    public ResponseEntity<List<PositionView>> livePositions() {
        return ResponseEntity.ok(positionService.getAllLiveViews());
    }

    // ─── Trades ───────────────────────────────────────────────────────────────

    @GetMapping("/trades")
    public ResponseEntity<List<Trade>> trades(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String pair) {
        String effectivePair = pair != null ? pair : tradingConfig.primaryPair();
        return ResponseEntity.ok(tradeRepository.findRecentTradesByPair(effectivePair, limit));
    }

    // ─── Stats ────────────────────────────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<TradingStats> stats() {
        return ResponseEntity.ok(tradeService.getStats());
    }

    @GetMapping("/pnl")
    public ResponseEntity<PnlBreakdown> pnl() {
        return ResponseEntity.ok(tradeService.getPnlBreakdown());
    }

    // ─── Control plane ────────────────────────────────────────────────────────

    @PostMapping("/emergency-stop")
    public ResponseEntity<String> emergencyStop() {
        log.warn("Emergency stop requested via /api/emergency-stop");
        botStateService.stop();
        alertService.botStopped("REST /api/emergency-stop");
        return ResponseEntity.ok("Bot stopped. Call POST /api/resume to restart trading.");
    }

    @PostMapping("/resume")
    public ResponseEntity<String> resume() {
        log.info("Resume requested via /api/resume");
        botStateService.resume();
        alertService.botResumed("REST /api/resume");
        return ResponseEntity.ok("Bot resumed. Next cycle will execute on schedule.");
    }

    @PostMapping("/config")
    public ResponseEntity<String> updateConfig(@Valid @RequestBody ConfigUpdateRequest req) {
        log.info("Config update requested: {}", req);

        configService.UpdateConfigs(req);

        log.info("Config updated successfully");
        return ResponseEntity.ok("Config updated. Changes take effect on the next trading cycle.");
    }

    // ─── Pairs ────────────────────────────────────────────────────────────────

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

    @GetMapping("/strategies")
    public ResponseEntity<List<Map<String, Object>>> strategies(
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String interval) {
        return ResponseEntity.ok(strategyService.getResult(pair, interval));
    }

    @GetMapping("/strategies/{strategyType}/positions")
    public ResponseEntity<List<PositionView>> strategyPositions(
            @PathVariable StrategyType strategyType,
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String interval) {
        return ResponseEntity.ok(strategyService.getPositionForStrategy(pair, interval, strategyType));
    }

    @GetMapping("/strategies/{strategyType}/trades")
    public ResponseEntity<List<Trade>> strategyTrades(
            @PathVariable StrategyType strategyType,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String interval) {
        String effectivePair     = pair != null ? pair : tradingConfig.primaryPair();
        String effectiveInterval = interval != null ? interval : tradingConfig.primaryInterval();
        return ResponseEntity.ok(tradeRepository.findRecentTradesByPairAndIntervalAndStrategy(
                effectivePair, effectiveInterval, strategyType, limit));
    }

    @GetMapping("/strategies/{strategyType}/history")
    public ResponseEntity<List<TradeHistoryEntry>> strategyHistory(
            @PathVariable StrategyType strategyType,
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String interval,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(strategyService.getTradeHistory(pair, interval, strategyType, from, to));
    }

    @GetMapping("/strategies/{strategyType}/stats")
    public ResponseEntity<TradingStats> strategyStats(
            @PathVariable StrategyType strategyType,
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String interval) {
        return ResponseEntity.ok(tradeService.getStatsForStrategy(pair, interval, strategyType));
    }

    @GetMapping("/strategies/{strategyType}/pnl")
    public ResponseEntity<PnlBreakdown> strategyPnl(
            @PathVariable StrategyType strategyType,
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String interval) {
        return ResponseEntity.ok(tradeService.getPnlBreakdownForStrategy(pair, interval, strategyType));
    }

    @GetMapping("/strategies/{strategyType}/signals")
    public ResponseEntity<List<SignalLog>> strategySignals(
            @PathVariable StrategyType strategyType,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String interval) {
        return ResponseEntity.ok(
                signalService.getSignalStrategies(pair, interval, strategyType, limit));
    }

    @GetMapping("/signals/summary")
    public ResponseEntity<List<Map<String, Object>>> signalsSummary(
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String interval) {
        return ResponseEntity.ok(signalService.getSummary(pair, interval));
    }

    @GetMapping("/signals/current")
    public ResponseEntity<List<CurrentSignal>> currentSignals(
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String interval) {
        return ResponseEntity.ok(signalService.getCurrentSignals(pair, interval));
    }

    // ─── Stats aggregation ────────────────────────────────────────────────────

    @GetMapping("/stats/all-triples")
    public ResponseEntity<List<TripleStats>> allTripleStats() {
        return ResponseEntity.ok(statsAggregationService.getAllTripleStats());
    }

    // ─── Triple enable/disable ────────────────────────────────────────────────

    @GetMapping("/triples/disabled")
    public ResponseEntity<List<DisabledTripleResponse>> disabledTriples() {
        return ResponseEntity.ok(tripleConfigService.listDisabled().stream()
                .map(DisabledTripleResponse::from)
                .toList());
    }

    @PostMapping("/triples/{pair}/{strategy}/{interval}/disable")
    public ResponseEntity<DisabledTripleResponse> disableTriple(
            @PathVariable String pair,
            @PathVariable StrategyType strategy,
            @PathVariable String interval,
            @RequestBody(required = false) TripleDisableRequest body) {
        validateTripleArgs(pair, interval);
        String reason = body == null ? null : body.reason();
        var saved = tripleConfigService.disable(pair, interval, strategy, reason);
        return ResponseEntity.ok(DisabledTripleResponse.from(saved));
    }

    @PostMapping("/triples/{pair}/{strategy}/{interval}/enable")
    public ResponseEntity<String> enableTriple(
            @PathVariable String pair,
            @PathVariable StrategyType strategy,
            @PathVariable String interval) {
        validateTripleArgs(pair, interval);
        tripleConfigService.enable(pair, interval, strategy);
        return ResponseEntity.ok("Triple enabled — new entries allowed on the next cycle.");
    }

    private void validateTripleArgs(String pair, String interval) {
        if (!tradingConfig.getPairs().contains(pair)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown pair: " + pair
                    + " (configured: " + tradingConfig.getPairs() + ")");
        }
        if (!tradingConfig.intervalLabels().contains(interval)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown interval: " + interval
                    + " (configured: " + tradingConfig.intervalLabels() + ")");
        }
    }

    // ─── Today summary ────────────────────────────────────────────────────────

    @GetMapping("/today")
    public ResponseEntity<TodaySummary> today() {
        return ResponseEntity.ok(todaySummaryService.build());
    }

    // ─── Activity feed ────────────────────────────────────────────────────────

    @GetMapping("/activity")
    public ResponseEntity<List<BotEvent>> activity(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) List<BotEventType> types) {
        return ResponseEntity.ok(
            botEventService.recent(limit, types == null ? null : Set.copyOf(types))
        );
    }

    // ─── Candles ──────────────────────────────────────────────────────────────

    @GetMapping("/candles")
    public ResponseEntity<List<CandleDto>> candles(
            @RequestParam String pair,
            @RequestParam String interval,
            @RequestParam(defaultValue = "500") int limit) {
        var all = candlestickRepository.findByPairAndIntervalOrderByTimestampAsc(pair, interval);
        var result = all.stream()
                .skip(Math.max(0, all.size() - limit))
                .map(c -> new CandleDto(
                        c.getTimestamp().toEpochSecond(java.time.ZoneOffset.UTC),
                        c.getOpenPrice(), c.getHighPrice(), c.getLowPrice(),
                        c.getClosePrice(), c.getVolume()))
                .toList();
        return ResponseEntity.ok(result);
    }

    // ─── Market sentiment ─────────────────────────────────────────────────────

    @GetMapping("/market/fear-greed")
    public ResponseEntity<FearGreedResponse> fearGreed() {
        FearGreedResponse result = fearGreedService.get();
        if (result == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/market/sentiment")
    public ResponseEntity<SentimentResponse> sentiment(
            @RequestParam(defaultValue = "BTC-EUR") String pair,
            @RequestParam(required = false) String interval,
            @RequestParam(defaultValue = "COMBINED")
                    com.stefo.revolut_trading_bot.model.enums.SentimentSource source) {

        String resolvedInterval = (interval != null && !interval.isBlank())
                ? interval : tradingConfig.primaryInterval();
        com.stefo.revolut_trading_bot.model.dto.SentimentScore score =
                sentimentService.scoreFor(pair, resolvedInterval, source);

        java.util.List<SentimentResponse.SubScore> subScores = null;
        if (source == com.stefo.revolut_trading_bot.model.enums.SentimentSource.COMBINED
                && !score.components().isEmpty()) {
            subScores = score.components().stream()
                    .map(c -> new SentimentResponse.SubScore(
                            c.source(), c.score(), c.volume(), c.sampleSize()))
                    .toList();
        }
        SentimentResponse response = new SentimentResponse(
                pair,
                source,
                resolvedInterval,
                score.score(),
                score.volume(),
                score.sampleSize(),
                score.computedAt(),
                !score.hasSignal(),
                subScores
        );
        return ResponseEntity.ok(response);
    }
}
