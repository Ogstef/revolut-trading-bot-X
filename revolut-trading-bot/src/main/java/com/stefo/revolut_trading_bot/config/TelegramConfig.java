package com.stefo.revolut_trading_bot.config;

import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Telegram Bot API configuration.
 *
 * Secrets (bot-token, chat-id) are injected via environment variables on the VPS
 * through /etc/revolut-trading-bot.env — same pattern as REVOLUT_API_KEY.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "telegram")
public class TelegramConfig {

    private boolean enabled = false;

    private String botToken;

    private String chatId;

    private String dailySummaryCron = "0 0 21 * * *";

    private boolean sendTradeNotifications = true;

    private boolean sendErrorNotifications = true;

    private boolean sendDailySummary = true;

    @Positive
    private int maxMessagesPerMinute = 20;
}
