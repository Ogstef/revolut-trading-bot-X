package com.stefo.revolut_trading_bot.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.stefo.revolut_trading_bot.model.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service-level wrapper around RevolutApiClient.
 *
 * Paths are taken from the official Revolut X API docs:
 *  - GET /candles/{symbol}?interval={minutes}   — OHLCV candles (authenticated)
 *  - GET /tickers?symbols={symbol}              — live bid/ask/last_price (authenticated)
 *  - GET /order-book/{symbol}?limit={n}         — order book depth (authenticated)
 *  - GET /balances                              — account balances (authenticated)
 *  - POST /orders                              — place order (authenticated)
 *  - GET /orders/{venue_order_id}              — order detail (authenticated)
 *  - DELETE /orders/{venue_order_id}           — cancel order (authenticated)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataClient {

    private final RevolutApiClient apiClient;

    // ─── Market Data (authenticated) ──────────────────────────────────────────

    /**
     * OHLCV candles for the given symbol.
     * @param symbol         e.g. "BTC-EUR"
     * @param intervalMinutes valid values: 1, 5, 15, 30, 60, 240, 1440, 2880, 5760, 10080, 20160, 40320
     */
    public List<CandleResponse> getCandles(String symbol, int intervalMinutes) {
        log.debug("Fetching candles for {} interval {}m", symbol, intervalMinutes);
        String query = "interval=" + intervalMinutes;
        return apiClient.getAuthenticated("/candles/" + symbol, query,
                new TypeReference<>() {});
    }

    /**
     * Real-time ticker snapshot (best bid, ask, mid, last traded price).
     * Returns a list; filter by symbol in the query param.
     */
    public List<TickerResponse> getTickers(String symbol) {
        log.debug("Fetching ticker for {}", symbol);
        return apiClient.getAuthenticated("/tickers", "symbols=" + symbol,
                new TypeReference<>() {});
    }

    /**
     * Order book snapshot for the given symbol.
     * @param symbol e.g. "BTC-EUR"
     * @param depth  number of price levels (1–20)
     */
    public OrderBookResponse getOrderBook(String symbol, int depth) {
        log.debug("Fetching order book for {} depth {}", symbol, depth);
        return apiClient.getAuthenticated("/order-book/" + symbol, "limit=" + depth,
                new TypeReference<>() {});
    }

    // ─── Account (authenticated) ───────────────────────────────────────────────

    public List<BalanceResponse> getBalances() {
        log.debug("Fetching account balances");
        return apiClient.getAuthenticated("/balances", null,
                new TypeReference<>() {});
    }

    // ─── Orders (authenticated) ────────────────────────────────────────────────

    /**
     * Place a market or limit order. Returns a slim acknowledgement.
     * Fetch GET /orders/{venue_order_id} for the full status.
     */
    public PlaceOrderResponse placeOrder(OrderRequest orderRequest) {
        log.info("Placing order: {} {} {}", orderRequest.side(), orderRequest.symbol(),
                orderRequest.clientOrderId());
        return apiClient.postAuthenticated("/orders", orderRequest,
                new TypeReference<>() {});
    }

    /**
     * Fetch full details of a single order by its venue-assigned ID.
     */
    public OrderResponse getOrder(String venueOrderId) {
        log.debug("Fetching order {}", venueOrderId);
        return apiClient.getAuthenticated("/orders/" + venueOrderId, null,
                new TypeReference<>() {});
    }

    public void cancelOrder(String venueOrderId) {
        log.info("Cancelling order {}", venueOrderId);
        apiClient.deleteAuthenticated("/orders/" + venueOrderId);
    }
}
