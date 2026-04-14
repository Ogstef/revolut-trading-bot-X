package com.stefo.revolut_trading_bot.controller;

import com.stefo.revolut_trading_bot.config.RevolutApiConfig;
import com.stefo.revolut_trading_bot.execution.OrderExecutionService;
import com.stefo.revolut_trading_bot.market.Ed25519SigningService;
import com.stefo.revolut_trading_bot.market.MarketDataClient;
import com.stefo.revolut_trading_bot.market.MarketDataService;
import com.stefo.revolut_trading_bot.portfolio.PortfolioService;
import com.stefo.revolut_trading_bot.portfolio.PortfolioSnapshot;
import com.stefo.revolut_trading_bot.portfolio.TradingStats;
import com.stefo.revolut_trading_bot.portfolio.TradeService;
import com.stefo.revolut_trading_bot.model.dto.BalanceResponse;
import com.stefo.revolut_trading_bot.model.dto.CandleResponse;
import com.stefo.revolut_trading_bot.model.dto.OrderBookResponse;
import com.stefo.revolut_trading_bot.model.dto.TickerResponse;
import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.entity.SignalLog;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import com.stefo.revolut_trading_bot.portfolio.PortfolioSnapshot;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
import com.stefo.revolut_trading_bot.model.enums.OrderSide;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.repository.PositionRepository;
import com.stefo.revolut_trading_bot.repository.TradeRepository;
import com.stefo.revolut_trading_bot.risk.RiskManager;
import com.stefo.revolut_trading_bot.risk.RiskValidationResult;
import com.stefo.revolut_trading_bot.strategy.Signal;
import com.stefo.revolut_trading_bot.strategy.SignalEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.Optional;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test/debugging endpoints — every layer of the system has at least one endpoint
 * so you can verify it end-to-end without running the full trading loop.
 *
 * All Revolut endpoints here are authenticated (Ed25519 signed).
 * None of them place real orders.
 */
@Slf4j
@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
public class TestController {

    private final MarketDataClient marketDataClient;
    private final MarketDataService marketDataService;
    private final Ed25519SigningService signingService;
    private final RevolutApiConfig apiConfig;
    private final SignalEngine signalEngine;
    private final RiskManager riskManager;
    private final OrderExecutionService orderExecutionService;
    private final PortfolioService portfolioService;
    private final TradeService tradeService;
    private final PositionRepository positionRepository;
    private final TradeRepository tradeRepository;
    private final com.stefo.revolut_trading_bot.config.TradingConfig tradingConfig;

    // ─── Infrastructure ───────────────────────────────────────────────────────

    /**
     * Health check — confirms app is up and signing key is loaded.
     * GET /test/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "timestamp", Instant.now().toString(),
                "signingKeyLoaded", signingService.isKeyLoaded()
        ));
    }

    /**
     * Verifies Ed25519 signing produces a valid signature without hitting the API.
     * GET /test/signing
     */
    @GetMapping("/signing")
    public ResponseEntity<Map<String, Object>> testSigning() {
        long timestamp = System.currentTimeMillis();
        String message = signingService.buildSignatureMessage(timestamp, "GET", "/test", null, null);
        String signature = signingService.sign(message);
        return ResponseEntity.ok(Map.of(
                "timestamp", timestamp,
                "message", message,
                "signature", signature,
                "keyLoaded", signingService.isKeyLoaded()
        ));
    }

    /**
     * Shows the exact signature message + headers Revolut expects for a GET /balances call.
     * Compare this against your working Postman collection to debug auth issues.
     * GET /test/auth/debug
     */
    @GetMapping("/auth/debug")
    public ResponseEntity<Map<String, Object>> authDebug() {
        String endpointPath = "/balances";
        String basePath = URI.create(apiConfig.getBaseUrl()).getPath();
        String fullPath = basePath + endpointPath;
        long timestamp = System.currentTimeMillis();

        String message = signingService.buildSignatureMessage(timestamp, "GET", fullPath, null, null);
        String signature = signingService.sign(message);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyLoaded", signingService.isKeyLoaded());
        result.put("baseUrl", apiConfig.getBaseUrl());
        result.put("fullPath", fullPath);
        result.put("timestamp", timestamp);
        result.put("messageToSign", message);
        result.put("signature", signature);
        result.put("headers", Map.of(
                "X-Revx-Timestamp", String.valueOf(timestamp),
                "X-Revx-Signature", signature
        ));

        log.info("Auth debug — message: [{}]", message);
        return ResponseEntity.ok(result);
    }

    // ─── Market Data (authenticated) ──────────────────────────────────────────

    /**
     * Fetches account balances. Primary auth + connectivity verification.
     * GET /test/auth/balance
     */
    @GetMapping("/auth/balance")
    public ResponseEntity<List<BalanceResponse>> authBalance() {
        log.info("TEST: fetching account balances");
        return ResponseEntity.ok(marketDataClient.getBalances());
    }

    /**
     * Fetches real-time ticker (bid, ask, mid, last_price) for a symbol.
     * Cheapest way to get the current BTC price.
     * GET /test/auth/ticker?symbol=BTC-EUR
     */
    @GetMapping("/auth/ticker")
    public ResponseEntity<List<TickerResponse>> authTicker(
            @RequestParam(defaultValue = "BTC-EUR") String symbol) {
        log.info("TEST: fetching ticker for {}", symbol);
        return ResponseEntity.ok(marketDataClient.getTickers(symbol));
    }

    /**
     * Fetches historical OHLCV candles.
     * Interval must be one of: 1, 5, 15, 30, 60, 240, 1440, 10080 (minutes).
     * GET /test/auth/candles?symbol=BTC-EUR&interval=15
     */
    @GetMapping("/auth/candles")
    public ResponseEntity<List<CandleResponse>> authCandles(
            @RequestParam(defaultValue = "BTC-EUR") String symbol,
            @RequestParam(defaultValue = "15") int interval) {
        log.info("TEST: fetching candles for {} interval {}m", symbol, interval);
        return ResponseEntity.ok(marketDataClient.getCandles(symbol, interval));
    }

    /**
     * Fetches order book snapshot (best bid/ask levels).
     * GET /test/auth/order-book?symbol=BTC-EUR&depth=5
     */
    @GetMapping("/auth/order-book")
    public ResponseEntity<OrderBookResponse> authOrderBook(
            @RequestParam(defaultValue = "BTC-EUR") String symbol,
            @RequestParam(defaultValue = "5") int depth) {
        log.info("TEST: fetching order book for {} depth {}", symbol, depth);
        return ResponseEntity.ok(marketDataClient.getOrderBook(symbol, depth));
    }

    // ─── Signal Engine ────────────────────────────────────────────────────────

    /**
     * Fetches fresh candles → builds BarSeries → runs EMA/RSI strategy → persists signal.
     * Full end-to-end test of the signal pipeline.
     * GET /test/signals/current
     */
    @GetMapping("/signals/current")
    public ResponseEntity<Map<String, Object>> signalCurrent() {
        log.info("TEST: evaluating fresh signal (API fetch + persist)");
        Signal signal = signalEngine.evaluateAndPersist();
        return ResponseEntity.ok(signalToMap(signal));
    }

    /**
     * Runs the strategy against the cached in-memory BarSeries — no API call, no DB write.
     * Use this after /signals/current to verify the strategy logic cheaply.
     * GET /test/signals/cached
     */
    @GetMapping("/signals/cached")
    public ResponseEntity<Map<String, Object>> signalCached() {
        log.info("TEST: evaluating signal from cache (no API call)");
        Signal signal = signalEngine.evaluateFromCache();
        return ResponseEntity.ok(signalToMap(signal));
    }

    /**
     * Returns recent signal log rows from the database.
     * GET /test/signals/history?limit=20
     */
    @GetMapping("/signals/history")
    public ResponseEntity<List<SignalLog>> signalHistory(
            @RequestParam(defaultValue = "20") int limit) {
        log.info("TEST: fetching last {} signal logs", limit);
        return ResponseEntity.ok(signalEngine.recentSignals(limit));
    }

    // ─── Risk Manager ─────────────────────────────────────────────────────────

    /**
     * Shows current risk metrics: open positions, daily PnL, consecutive losses,
     * and whether any circuit breaker is tripped.
     * GET /test/risk/status?balance=1000
     */
    @GetMapping("/risk/status")
    public ResponseEntity<RiskManager.RiskStatus> riskStatus(
            @RequestParam(defaultValue = "1000") BigDecimal balance) {
        return ResponseEntity.ok(riskManager.currentStatus(balance));
    }

    /**
     * Validates whether a new trade would be approved right now.
     * GET /test/risk/validate?balance=1000
     */
    @GetMapping("/risk/validate")
    public ResponseEntity<Map<String, Object>> riskValidate(
            @RequestParam(defaultValue = "1000") BigDecimal balance) {
        RiskValidationResult result = riskManager.validate(balance);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("approved", result.approved());
        map.put("reason", result.reason());
        map.put("positionSizeEur", result.positionSizeEur());
        return ResponseEntity.ok(map);
    }

    // ─── Paper Trading ────────────────────────────────────────────────────────

    /**
     * Runs the full pipeline: fetch signal → risk check → open/close paper position.
     * Defaults to trading.paper-balance from config; override with ?balance= to test
     * how the bot behaves with different account sizes.
     * POST /test/paper/simulate?balance=50000
     */
    @PostMapping("/paper/simulate")
    public ResponseEntity<Map<String, Object>> paperSimulate(
            @RequestParam(required = false) BigDecimal balance) {
        if (balance == null) {
            balance = tradingConfig.getPaperBalance();
        }
        log.info("TEST: paper simulate with balance={}", balance);

        Signal signal = signalEngine.evaluateAndPersist();
        BigDecimal currentPrice = marketDataService.getCurrentPrice();

        orderExecutionService.monitorPositions(currentPrice);
        Optional<Position> position = orderExecutionService.executeSignal(signal, balance, currentPrice);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("signal", signalToMap(signal));
        result.put("currentPrice", currentPrice);
        result.put("positionOpened", position.isPresent());
        position.ifPresent(p -> result.put("positionId", p.getId()));
        return ResponseEntity.ok(result);
    }

    /**
     * Lists all currently open paper positions.
     * GET /test/paper/positions
     */
    @GetMapping("/paper/positions")
    public ResponseEntity<List<Position>> paperPositions() {
        return ResponseEntity.ok(positionRepository.findByStatus(OrderStatus.OPEN));
    }

    /**
     * Lists recent closed trades with PnL.
     * GET /test/paper/trades?limit=20
     */
    @GetMapping("/paper/trades")
    public ResponseEntity<List<Trade>> paperTrades(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(
                tradeRepository.findRecentTradesByPair("BTC-EUR", limit));
    }

    // ─── Portfolio ────────────────────────────────────────────────────────────

    /**
     * Live portfolio snapshot — open positions + unrealised PnL at the current price.
     * GET /test/portfolio/snapshot
     */
    @GetMapping("/portfolio/snapshot")
    public ResponseEntity<PortfolioSnapshot> portfolioSnapshot() {
        BigDecimal currentPrice = marketDataService.getCurrentPrice();
        return ResponseEntity.ok(portfolioService.getSnapshot(currentPrice));
    }

    /**
     * Aggregate statistics across all closed trades: win rate, total PnL, expectancy.
     * GET /test/portfolio/stats
     */
    @GetMapping("/portfolio/stats")
    public ResponseEntity<TradingStats> portfolioStats() {
        return ResponseEntity.ok(tradeService.getStats());
    }

    // ─── Multi-strategy test endpoints (Phase 7) ─────────────────────────────

    /**
     * Returns recent signal logs filtered to a single strategy.
     * GET /test/signals/by-strategy?strategy=MACD&limit=20
     */
    @GetMapping("/signals/by-strategy")
    public ResponseEntity<List<SignalLog>> signalsByStrategy(
            @RequestParam StrategyType strategy,
            @RequestParam(defaultValue = "20") int limit) {
        log.info("TEST: fetching last {} signals for strategy={}", limit, strategy);
        return ResponseEntity.ok(signalEngine.recentSignalsForStrategy(strategy, limit));
    }

    /**
     * BUY/SELL/HOLD signal counts broken down per strategy.
     * GET /test/signals/strategy-summary
     */
    @GetMapping("/signals/strategy-summary")
    public ResponseEntity<Map<String, Object>> signalStrategySummary() {
        log.info("TEST: fetching signal strategy summary");
        Map<String, Integer> rows = signalEngine.registeredStrategies().stream()
                .collect(java.util.stream.Collectors.toMap(
                        StrategyType::name,
                        strategyType -> signalEngine.recentSignalsForStrategy(strategyType, 100).size()
                ));
        return ResponseEntity.ok(Map.of("strategyCounts", rows));
    }

    /**
     * Portfolio snapshot scoped to a single strategy.
     * GET /test/strategies/{strategyType}/snapshot
     */
    @GetMapping("/strategies/{strategyType}/snapshot")
    public ResponseEntity<PortfolioSnapshot> strategySnapshot(@PathVariable StrategyType strategyType) {
        BigDecimal currentPrice = marketDataService.getCurrentPrice();
        List<Position> openPositions = positionRepository.findByStatusAndStrategyName(
                OrderStatus.OPEN, strategyType);

        BigDecimal totalInvested = openPositions.stream()
                .map(p -> p.getEntryPrice().multiply(p.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal unrealisedPnl = openPositions.stream()
                .map(p -> {
                    BigDecimal diff = currentPrice.subtract(p.getEntryPrice());
                    if (p.getSide() == OrderSide.SELL) {
                        diff = diff.negate();
                    }
                    return diff.multiply(p.getQuantity());
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal unrealisedPnlPct = totalInvested.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : unrealisedPnl.divide(totalInvested, 8, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(4, RoundingMode.HALF_UP);

        return ResponseEntity.ok(new PortfolioSnapshot(
                tradingConfig.primaryPair() + " [" + strategyType.getDisplayName() + "]",
                openPositions.size(),
                totalInvested.setScale(2, RoundingMode.HALF_UP),
                unrealisedPnl.setScale(2, RoundingMode.HALF_UP),
                unrealisedPnlPct,
                currentPrice,
                java.time.Instant.now()
        ));
    }

    private Map<String, Object> signalToMap(Signal signal) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("signal", signal.type());
        result.put("confidence", signal.confidence());
        result.put("pair", signal.pair());
        result.put("strategyType", signal.strategyType());
        result.put("evaluatedAt", signal.evaluatedAt().toString());
        result.put("reason", signal.reason());
        result.put("emaShort", signal.emaShort());
        result.put("emaLong", signal.emaLong());
        result.put("rsi", signal.rsi());
        result.put("currentPrice", signal.currentPrice());
        return result;
    }
}
