package com.stefo.revolut_trading_bot.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.stefo.revolut_trading_bot.model.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataClient {

    private final RevolutApiClient apiClient;

    // --- Public endpoints (no auth) ---

    public List<SymbolResponse> getSymbols() {
        log.debug("Fetching available symbols");
        return apiClient.getPublic("/public/symbols", null,
                new TypeReference<>() {});
    }

    public List<MarketTradeResponse> getPublicTrades(String symbol) {
        log.debug("Fetching public trades for {}", symbol);
        return apiClient.getPublic("/public/trades", "symbol=" + symbol,
                new TypeReference<>() {});
    }

    public OrderBookResponse getOrderBook(String symbol) {
        log.debug("Fetching order book for {}", symbol);
        return apiClient.getPublic("/public/order-book", "symbol=" + symbol,
                new TypeReference<>() {});
    }

    // --- Authenticated endpoints ---

    public List<CandleResponse> getCandles(String symbol, String interval) {
        log.debug("Fetching candles for {} interval {}", symbol, interval);
        String query = "symbol=" + symbol + "&interval=" + interval;
        return apiClient.getAuthenticated("/market-data/candles", query,
                new TypeReference<>() {});
    }

    public List<BalanceResponse> getBalances() {
        log.debug("Fetching account balances");
        return apiClient.getAuthenticated("/balance", null,
                new TypeReference<>() {});
    }

    public OrderResponse placeOrder(OrderRequest orderRequest) {
        log.info("Placing order: {} {} {}", orderRequest.side(), orderRequest.symbol(),
                orderRequest.clientOrderId());
        return apiClient.postAuthenticated("/orders", orderRequest,
                new TypeReference<>() {});
    }

    public List<OrderResponse> getActiveOrders() {
        log.debug("Fetching active orders");
        return apiClient.getAuthenticated("/orders/active", null,
                new TypeReference<>() {});
    }

    public void cancelOrder(String orderId) {
        log.info("Cancelling order {}", orderId);
        apiClient.deleteAuthenticated("/orders/" + orderId);
    }

    public List<OrderResponse> getTradeHistory() {
        log.debug("Fetching trade history");
        return apiClient.getAuthenticated("/trades", null,
                new TypeReference<>() {});
    }
}
