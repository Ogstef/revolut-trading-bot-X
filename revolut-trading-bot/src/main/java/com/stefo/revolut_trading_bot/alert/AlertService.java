package com.stefo.revolut_trading_bot.alert;

import com.stefo.revolut_trading_bot.config.TelegramConfig;
import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import com.stefo.revolut_trading_bot.service.BotEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Publishes structured trading events.
 *
 * V1: SLF4J structured log messages — searchable in the log file.
 * V2: Telegram notifications via TelegramClient (fire-and-forget).
 *
 * All methods are fire-and-forget — never throw.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final BotEventService botEventService;
    private final TelegramClient telegramClient;
    private final TelegramMessageFormatter telegramFormatter;
    private final TelegramConfig telegramConfig;

    // ─── Trade lifecycle ──────────────────────────────────────────────────────

    public void positionOpened(Position position) {
        log.info("[TRADE OPEN]  pair={} side={} entryPrice={} quantity={} tp={} sl={} positionId={}",
                position.getPair(), position.getSide(),
                position.getEntryPrice(), position.getQuantity(),
                position.getTakeProfit(), position.getStopLoss(),
                position.getId());
        botEventService.recordPositionOpened(position, position.getSignalReason());
        if (telegramConfig.isSendTradeNotifications()) {
            telegramClient.sendMessage(telegramFormatter.formatPositionOpened(position));
        }
    }

    public void positionClosed(Position position, Trade trade) {
        log.info("[TRADE CLOSE] pair={} side={} entryPrice={} exitPrice={} pnl={} pnlPct={}% reason={} positionId={}",
                position.getPair(), position.getSide(),
                trade.getEntryPrice(), trade.getExitPrice(),
                trade.getPnl(), trade.getPnlPct(),
                trade.getExitReason(), position.getId());
        botEventService.recordPositionClosed(trade);
        if (telegramConfig.isSendTradeNotifications()) {
            telegramClient.sendMessage(telegramFormatter.formatPositionClosed(position, trade));
        }
    }

    // ─── Circuit breakers ─────────────────────────────────────────────────────

    public void circuitBreakerTripped(String reason) {
        log.warn("[CIRCUIT BREAKER] Trading halted — {}", reason);
        if (telegramConfig.isSendErrorNotifications()) {
            telegramClient.sendMessage(telegramFormatter.formatCircuitBreakerTripped(reason));
        }
    }

    // ─── Bot lifecycle ────────────────────────────────────────────────────────

    public void botStopped(String triggeredBy) {
        log.warn("[BOT STOP] Emergency stop triggered by={}", triggeredBy);
        botEventService.recordBotStopped(triggeredBy);
        if (telegramConfig.isSendErrorNotifications()) {
            telegramClient.sendMessage(telegramFormatter.formatBotStopped(triggeredBy));
        }
    }

    public void botResumed(String triggeredBy) {
        log.info("[BOT RESUME] Trading resumed by={}", triggeredBy);
        botEventService.recordBotResumed(triggeredBy);
        telegramClient.sendMessage(telegramFormatter.formatBotResumed(triggeredBy));
    }

    // ─── Trading cycle errors ─────────────────────────────────────────────────

    public void tradingCycleFailed(Exception e) {
        log.error("[CYCLE FAIL] {}", e.getMessage());
        if (telegramConfig.isSendErrorNotifications()) {
            telegramClient.sendMessage(telegramFormatter.formatTradingCycleError(e.getMessage()));
        }
    }

    // ─── Risk events ──────────────────────────────────────────────────────────

    public void tradeRejected(String pair, String reason) {
        log.info("[TRADE REJECTED] pair={} reason={}", pair, reason);
    }

    public void dailyPnlAlert(BigDecimal dailyPnl, BigDecimal threshold) {
        log.warn("[DAILY PNL ALERT] dailyPnl={} threshold={}", dailyPnl, threshold);
    }

    // ─── LLM budget warnings ──────────────────────────────────────────────────

    /**
     * Fires once per month when the Haiku monthly spend crosses {@code alertPct}% of the hard cap.
     * Idempotency (one-shot per month) is enforced by the caller, not here.
     */
    public void sentimentBudgetWarning(BigDecimal spentUsd, BigDecimal monthlyCapUsd, int alertPct) {
        log.warn("[SENTIMENT BUDGET] monthly spend {} USD crossed {}% of cap {} USD",
                spentUsd, alertPct, monthlyCapUsd);
        if (telegramConfig.isSendErrorNotifications()) {
            String message = String.format(
                    "⚠️ Sentiment classifier at %d%%+ of monthly budget%nSpent: $%s / $%s cap",
                    alertPct, spentUsd.toPlainString(), monthlyCapUsd.toPlainString());
            telegramClient.sendMessage(message);
        }
    }
}
