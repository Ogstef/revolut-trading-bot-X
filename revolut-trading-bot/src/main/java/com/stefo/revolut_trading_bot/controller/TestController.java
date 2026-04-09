package com.stefo.revolut_trading_bot.controller;

import com.stefo.revolut_trading_bot.config.RevolutApiConfig;
import com.stefo.revolut_trading_bot.market.Ed25519SigningService;
import com.stefo.revolut_trading_bot.market.MarketDataClient;
import com.stefo.revolut_trading_bot.model.dto.BalanceResponse;
import com.stefo.revolut_trading_bot.model.dto.CandleResponse;
import com.stefo.revolut_trading_bot.model.dto.MarketTradeResponse;
import com.stefo.revolut_trading_bot.model.dto.OrderBookResponse;
import com.stefo.revolut_trading_bot.model.dto.SymbolResponse;
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

@Slf4j
@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
public class TestController {

    private final MarketDataClient marketDataClient;
    private final Ed25519SigningService signingService;
    private final RevolutApiConfig apiConfig;

    /**
     * Health check — confirms the app is running and the signing key is loaded.
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
     * Verifies Ed25519 signing is working by producing a test signature.
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
     * Fetches public symbols — no auth required, confirms basic connectivity.
     * GET /test/public/symbols
     */
    @GetMapping("/public/symbols")
    public ResponseEntity<List<SymbolResponse>> publicSymbols() {
        log.info("TEST: fetching public symbols");
        return ResponseEntity.ok(marketDataClient.getSymbols());
    }

    /**
     * Fetches public trades — no auth required.
     * GET /test/public/trades?symbol=BTC-EUR
     */
    @GetMapping("/public/trades")
    public ResponseEntity<List<MarketTradeResponse>> publicTrades(
            @RequestParam(defaultValue = "BTC-EUR") String symbol) {
        log.info("TEST: fetching public trades for {}", symbol);
        return ResponseEntity.ok(marketDataClient.getPublicTrades(symbol));
    }

    /**
     * Fetches public order book — no auth required.
     * GET /test/public/order-book?symbol=BTC-EUR
     */
    @GetMapping("/public/order-book")
    public ResponseEntity<OrderBookResponse> publicOrderBook(
            @RequestParam(defaultValue = "BTC-EUR") String symbol) {
        log.info("TEST: fetching order book for {}", symbol);
        return ResponseEntity.ok(marketDataClient.getOrderBook(symbol));
    }

    /**
     * Fetches account balances — requires valid API key + Ed25519 signature.
     * This is the primary auth verification endpoint.
     * GET /test/auth/balance
     */
    @GetMapping("/auth/balance")
    public ResponseEntity<List<BalanceResponse>> authBalance() {
        log.info("TEST: fetching authenticated balance");
        return ResponseEntity.ok(marketDataClient.getBalances());
    }

    /**
     * Fetches candles — requires auth.
     * GET /test/auth/candles?symbol=BTC-EUR&interval=15m
     */
    @GetMapping("/auth/candles")
    public ResponseEntity<List<CandleResponse>> authCandles(
            @RequestParam(defaultValue = "BTC-EUR") String symbol,
            @RequestParam(defaultValue = "15m") String interval) {
        log.info("TEST: fetching authenticated candles for {} interval {}", symbol, interval);
        return ResponseEntity.ok(marketDataClient.getCandles(symbol, interval));
    }

    /**
     * Fetches active orders — requires auth.
     * GET /test/auth/orders/active
     */
    @GetMapping("/auth/orders/active")
    public ResponseEntity<Object> authActiveOrders() {
        log.info("TEST: fetching active orders");
        return ResponseEntity.ok(marketDataClient.getActiveOrders());
    }

    /**
     * Shows the exact signature message and headers that would be sent to Revolut
     * for a GET /balance call — without actually sending it. Use this to compare
     * against your working Postman collection.
     * GET /test/auth/debug
     */
    @GetMapping("/auth/debug")
    public ResponseEntity<Map<String, Object>> authDebug() {
        String endpointPath = "/balance";
        String basePath = URI.create(apiConfig.getBaseUrl()).getPath(); // e.g. /api/1.0
        String fullPath = basePath + endpointPath;                      // e.g. /api/1.0/balance
        long timestamp = System.currentTimeMillis();

        String message = signingService.buildSignatureMessage(timestamp, "GET", fullPath, null, null);
        String signature = signingService.sign(message);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyLoaded", signingService.isKeyLoaded());
        result.put("baseUrl", apiConfig.getBaseUrl());
        result.put("basePath", basePath);
        result.put("fullPath", fullPath);
        result.put("timestamp", timestamp);
        result.put("messageToSign", message);
        result.put("signature", signature);
        result.put("headers", Map.of(
                "X-Revx-API-Key", apiConfig.getApiKey(),
                "X-Revx-Timestamp", String.valueOf(timestamp),
                "X-Revx-Signature", signature
        ));

        log.info("Auth debug — message to sign: [{}]", message);
        return ResponseEntity.ok(result);
    }
}
