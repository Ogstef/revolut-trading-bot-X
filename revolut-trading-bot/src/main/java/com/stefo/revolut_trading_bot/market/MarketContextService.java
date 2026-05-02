package com.stefo.revolut_trading_bot.market;

import com.stefo.revolut_trading_bot.model.dto.FearGreedResponse;
import com.stefo.revolut_trading_bot.model.dto.OrderBookResponse;
import com.stefo.revolut_trading_bot.service.FearGreedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Aggregates Fear & Greed (macro) and order-book imbalance (microstructure) into a
 * per-pair snapshot consumed by {@code MarketContextStrategy}.
 *
 * Cache is pair-keyed (the order book is per-pair, not per-interval) and refreshes once
 * per {@link #ORDER_BOOK_TTL} ≈ the trading-loop period. With 3 pairs that is ~360
 * order-book calls/hour, well under Revolut's 1000 req/min general limit.
 *
 * Failure tolerance is non-negotiable: a single failed fetch must not cascade into the
 * 240-portfolio trading loop. Both inputs are nullable in {@link MarketContext}; callers
 * treat null as "no signal" rather than a default value.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketContextService {

    private static final Duration ORDER_BOOK_TTL = Duration.ofSeconds(30);
    private static final int OB_DEPTH = 5;

    private final FearGreedService fearGreedService;
    private final MarketDataClient marketDataClient;

    private final ConcurrentMap<String, MarketContext> cache = new ConcurrentHashMap<>();

    public MarketContext getContext(String pair) {
        MarketContext cached = cache.get(pair);
        if (cached != null && Instant.now().isBefore(cached.snapshotAt().plus(ORDER_BOOK_TTL))) {
            return cached;
        }
        return refresh(pair);
    }

    private synchronized MarketContext refresh(String pair) {
        MarketContext cached = cache.get(pair);
        if (cached != null && Instant.now().isBefore(cached.snapshotAt().plus(ORDER_BOOK_TTL))) {
            return cached;
        }
        FearGreedResponse fg = safeFearGreed();
        BigDecimal ratio = safeBidAskRatio(pair);
        MarketContext fresh = new MarketContext(
                pair,
                fg == null ? null : fg.value(),
                fg == null ? null : fg.classification(),
                ratio,
                Instant.now()
        );
        cache.put(pair, fresh);
        log.debug("MarketContext[{}]: F&G={} ({}), bidAskRatio={}",
                pair, fresh.fearGreedValue(), fresh.fearGreedClassification(), fresh.bidAskRatio());
        return fresh;
    }

    private FearGreedResponse safeFearGreed() {
        try {
            return fearGreedService.get();
        } catch (Exception e) {
            log.warn("Fear & Greed fetch failed: {}", e.getMessage());
            return null;
        }
    }

    private BigDecimal safeBidAskRatio(String pair) {
        try {
            OrderBookResponse book = marketDataClient.getOrderBook(pair, OB_DEPTH);
            if (book == null) return null;
            return computeRatio(book.bids(), book.asks());
        } catch (Exception e) {
            log.warn("Order book fetch failed for {}: {}", pair, e.getMessage());
            return null;
        }
    }

    private static BigDecimal computeRatio(List<OrderBookResponse.PriceLevel> bids,
                                           List<OrderBookResponse.PriceLevel> asks) {
        if (bids == null || asks == null || bids.isEmpty() || asks.isEmpty()) return null;
        BigDecimal totalBid = bids.stream()
                .map(OrderBookResponse.PriceLevel::quantity)
                .filter(q -> q != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAsk = asks.stream()
                .map(OrderBookResponse.PriceLevel::quantity)
                .filter(q -> q != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalAsk.signum() == 0) return null;
        return totalBid.divide(totalAsk, 4, RoundingMode.HALF_UP);
    }
}
