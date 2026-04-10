package com.stefo.revolut_trading_bot.controller;

import com.stefo.revolut_trading_bot.alert.AlertService;
import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.market.MarketDataService;
import com.stefo.revolut_trading_bot.model.dto.*;
import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
import com.stefo.revolut_trading_bot.portfolio.PortfolioService;
import com.stefo.revolut_trading_bot.portfolio.TradingStats;
import com.stefo.revolut_trading_bot.portfolio.TradeService;
import com.stefo.revolut_trading_bot.repository.PositionRepository;
import com.stefo.revolut_trading_bot.repository.TradeRepository;
import com.stefo.revolut_trading_bot.risk.RiskManager;
import com.stefo.revolut_trading_bot.scheduler.BotStateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

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
    private final RiskManager riskManager;
    private final PortfolioService portfolioService;
    private final TradeService tradeService;
    private final MarketDataService marketDataService;
    private final PositionRepository positionRepository;
    private final TradeRepository tradeRepository;
    private final AlertService alertService;

    // ─── Status ───────────────────────────────────────────────────────────────

    /**
     * High-level bot health — running state, mode, circuit breakers, daily PnL.
     * GET /api/status
     */
    @GetMapping("/status")
    public ResponseEntity<BotStatusResponse> status() {
        BigDecimal balance = tradingConfig.getPaperBalance();
        RiskManager.RiskStatus risk = riskManager.currentStatus(balance);

        BotStatusResponse response = new BotStatusResponse(
                botStateService.isActive(),
                tradingConfig.getMode(),
                tradingConfig.getPair(),
                risk.openPositions(),
                risk.dailyPnl(),
                risk.consecutiveLosses(),
                risk.anyCircuitBreakerTripped(),
                Instant.now()
        );
        return ResponseEntity.ok(response);
    }

    // ─── Positions ────────────────────────────────────────────────────────────

    /**
     * All currently open positions enriched with live unrealised PnL.
     * GET /api/positions
     */
    @GetMapping("/positions")
    public ResponseEntity<List<PositionView>> positions() {
        BigDecimal currentPrice = marketDataService.getCurrentPrice();
        List<Position> open = positionRepository.findByStatus(OrderStatus.OPEN);

        List<PositionView> views = open.stream()
                .map(p -> toPositionView(p, currentPrice))
                .toList();
        return ResponseEntity.ok(views);
    }

    // ─── Trades ───────────────────────────────────────────────────────────────

    /**
     * Recent closed trades with entry/exit prices and PnL.
     * GET /api/trades?limit=50
     */
    @GetMapping("/trades")
    public ResponseEntity<List<Trade>> trades(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(
                tradeRepository.findRecentTradesByPair(tradingConfig.getPair(), limit));
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

        TradingConfig.Risk risk = tradingConfig.getRisk();
        TradingConfig.Strategy strategy = tradingConfig.getStrategy();

        if (req.maxPositionPct() != null)       risk.setMaxPositionPct(req.maxPositionPct());
        if (req.maxConcurrentPositions() != null) risk.setMaxConcurrentPositions(req.maxConcurrentPositions());
        if (req.maxDailyLossPct() != null)      risk.setMaxDailyLossPct(req.maxDailyLossPct());
        if (req.maxConsecutiveLosses() != null)  risk.setMaxConsecutiveLosses(req.maxConsecutiveLosses());
        if (req.takeProfitPct() != null)         risk.setTakeProfitPct(req.takeProfitPct());
        if (req.stopLossPct() != null)           risk.setStopLossPct(req.stopLossPct());

        if (req.emaShortPeriod() != null)  strategy.setEmaShortPeriod(req.emaShortPeriod());
        if (req.emaLongPeriod() != null)   strategy.setEmaLongPeriod(req.emaLongPeriod());
        if (req.rsiPeriod() != null)       strategy.setRsiPeriod(req.rsiPeriod());
        if (req.rsiOverbought() != null)   strategy.setRsiOverbought(req.rsiOverbought());
        if (req.rsiOversold() != null)     strategy.setRsiOversold(req.rsiOversold());

        if (req.paperBalance() != null)    tradingConfig.setPaperBalance(req.paperBalance());

        log.info("Config updated successfully");
        return ResponseEntity.ok("Config updated. Changes take effect on the next trading cycle.");
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private PositionView toPositionView(Position p, BigDecimal currentPrice) {
        // Unrealised PnL = (currentPrice - entryPrice) × quantity
        // For SELL (short): reversed
        BigDecimal priceDiff = currentPrice.subtract(p.getEntryPrice());
        if (p.getSide().name().equals("SELL")) {
            priceDiff = priceDiff.negate();
        }
        BigDecimal unrealisedPnl = priceDiff.multiply(p.getQuantity()).setScale(2, RoundingMode.HALF_UP);

        BigDecimal invested = p.getEntryPrice().multiply(p.getQuantity());
        BigDecimal unrealisedPnlPct = invested.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : unrealisedPnl.divide(invested, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);

        return new PositionView(
                p.getId(), p.getPair(), p.getSide().name(),
                p.getEntryPrice(), p.getQuantity(),
                p.getTakeProfit(), p.getStopLoss(),
                currentPrice, unrealisedPnl, unrealisedPnlPct,
                p.getSignalReason(), p.getOpenedAt()
        );
    }
}
