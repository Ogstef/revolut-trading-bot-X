package com.stefo.revolut_trading_bot.alert;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stefo.revolut_trading_bot.config.TelegramConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fire-and-forget Telegram Bot API client.
 *
 * All public methods swallow exceptions — a Telegram failure must NEVER affect trading logic.
 * Rate-limited via a simple token bucket (maxMessagesPerMinute tokens, refilled every 60 s).
 */
@Slf4j
@Component
public class TelegramClient {

    private static final String TELEGRAM_API = "https://api.telegram.org/bot%s/sendMessage";

    private final TelegramConfig config;
    private final RestClient restClient;

    private final AtomicInteger tokensAvailable;
    private volatile Instant lastRefill;

    public TelegramClient(TelegramConfig config) {
        this.config = config;
        this.restClient = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(5))
                                .build()))
                .build();
        this.tokensAvailable = new AtomicInteger(config.getMaxMessagesPerMinute());
        this.lastRefill = Instant.now();
    }

    /**
     * Sends a message to the configured Telegram chat. Never throws.
     *
     * @return true if the message was sent successfully, false if skipped or failed
     */
    public boolean sendMessage(String text) {
        if (!config.isEnabled()) {
            log.debug("Telegram disabled — skipping message");
            return false;
        }
        if (text == null || text.isBlank()) {
            return false;
        }
        if (!tryAcquireToken()) {
            log.warn("Telegram rate limit reached — dropping message: {}",
                    text.substring(0, Math.min(text.length(), 80)));
            return false;
        }
        try {
            String url = String.format(TELEGRAM_API, config.getBotToken());
            restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TelegramSendRequest(config.getChatId(), text, "HTML"))
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Telegram message sent ({} chars)", text.length());
            return true;
        } catch (Exception e) {
            log.warn("Telegram send failed: {}", e.getMessage());
            return false;
        }
    }

    // ─── Rate limiting ───────────────────────────────────────────────────────

    private boolean tryAcquireToken() {
        Instant now = Instant.now();
        if (Duration.between(lastRefill, now).toSeconds() >= 60) {
            synchronized (this) {
                if (Duration.between(lastRefill, now).toSeconds() >= 60) {
                    tokensAvailable.set(config.getMaxMessagesPerMinute());
                    lastRefill = now;
                }
            }
        }
        return tokensAvailable.getAndUpdate(current -> current > 0 ? current - 1 : 0) > 0;
    }

    // ─── Internal DTO ────────────────────────────────────────────────────────

    private record TelegramSendRequest(
            @JsonProperty("chat_id") String chatId,
            String text,
            @JsonProperty("parse_mode") String parseMode
    ) {}
}
