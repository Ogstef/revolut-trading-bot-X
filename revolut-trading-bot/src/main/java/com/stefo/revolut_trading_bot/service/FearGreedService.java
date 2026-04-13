package com.stefo.revolut_trading_bot.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stefo.revolut_trading_bot.model.dto.FearGreedResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Fetches the Crypto Fear & Greed Index from alternative.me.
 * Results are cached for 1 hour — the index only updates once per day.
 */
@Slf4j
@Service
public class FearGreedService {

    private static final String API_URL = "https://api.alternative.me/fng/?limit=1";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final RestClient restClient = RestClient.create();

    private volatile FearGreedResponse cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public FearGreedResponse get() {
        if (cached == null || Instant.now().isAfter(cachedAt.plus(CACHE_TTL))) {
            refresh();
        }
        return cached;
    }

    private synchronized void refresh() {
        if (cached != null && Instant.now().isBefore(cachedAt.plus(CACHE_TTL))) {
            return; // already refreshed by another thread
        }
        try {
            FngApiResponse response = restClient.get()
                    .uri(API_URL)
                    .retrieve()
                    .body(FngApiResponse.class);

            if (response != null && response.data() != null && !response.data().isEmpty()) {
                FngDataItem item = response.data().getFirst();
                cached = new FearGreedResponse(
                        Integer.parseInt(item.value()),
                        item.valueClassification(),
                        Long.parseLong(item.timestamp())
                );
                cachedAt = Instant.now();
                log.info("Fear & Greed Index: {} ({})", cached.value(), cached.classification());
            }
        } catch (Exception e) {
            log.warn("Failed to fetch Fear & Greed Index: {}", e.getMessage());
        }
    }

    // ── API response DTOs (private — only used for parsing) ──────────────────

    private record FngApiResponse(List<FngDataItem> data) {}

    private record FngDataItem(
            String value,
            @JsonProperty("value_classification") String valueClassification,
            String timestamp
    ) {}
}
