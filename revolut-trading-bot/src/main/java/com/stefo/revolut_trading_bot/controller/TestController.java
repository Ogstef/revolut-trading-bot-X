package com.stefo.revolut_trading_bot.controller;

import com.stefo.revolut_trading_bot.config.RevolutApiConfig;
import com.stefo.revolut_trading_bot.market.Ed25519SigningService;
import com.stefo.revolut_trading_bot.market.MarketDataClient;
import com.stefo.revolut_trading_bot.model.dto.BalanceResponse;
import com.stefo.revolut_trading_bot.model.dto.CandleResponse;
import com.stefo.revolut_trading_bot.model.dto.OrderBookResponse;
import com.stefo.revolut_trading_bot.model.dto.TickerResponse;
import com.stefo.revolut_trading_bot.model.entity.SignalLog;
import com.stefo.revolut_trading_bot.strategy.Signal;
import com.stefo.revolut_trading_bot.strategy.SignalEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
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
    private final Ed25519SigningService signingService;
    private final RevolutApiConfig apiConfig;
    private final SignalEngine signalEngine;

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
     * Interval must be one of: 1, 5, 15, 30, 60, 240, 1440 (minutes).
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

    private Map<String, Object> signalToMap(Signal signal) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("signal", signal.type());
        result.put("confidence", signal.confidence());
        result.put("pair", signal.pair());
        result.put("evaluatedAt", signal.evaluatedAt().toString());
        result.put("reason", signal.reason());
        result.put("emaShort", signal.emaShort());
        result.put("emaLong", signal.emaLong());
        result.put("rsi", signal.rsi());
        result.put("currentPrice", signal.currentPrice());
        return result;
    }
}
