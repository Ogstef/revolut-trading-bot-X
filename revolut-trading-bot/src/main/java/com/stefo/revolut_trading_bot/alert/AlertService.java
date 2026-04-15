package com.stefo.revolut_trading_bot.alert;

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
 * V2: Add Telegram webhook by injecting a TelegramClient here.
 *
 * All methods are fire-and-forget — never throw.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final BotEventService botEventService;

    // ─── Trade lifecycle ──────────────────────────────────────────────────────

    public void positionOpened(Position position) {
        log.info("[TRADE OPEN]  pair={} side={} entryPrice={} quantity={} tp={} sl={} positionId={}",
                position.getPair(), position.getSide(),
                position.getEntryPrice(), position.getQuantity(),
                position.getTakeProfit(), position.getStopLoss(),
                position.getId());
        botEventService.recordPositionOpened(position, position.getSignalReason());
    }

    public void positionClosed(Position position, Trade trade) {
        log.info("[TRADE CLOSE] pair={} side={} entryPrice={} exitPrice={} pnl={} pnlPct={}% reason={} positionId={}",
                position.getPair(), position.getSide(),
                trade.getEntryPrice(), trade.getExitPrice(),
                trade.getPnl(), trade.getPnlPct(),
                trade.getExitReason(), position.getId());
        botEventService.recordPositionClosed(trade);
    }

    // ─── Circuit breakers ─────────────────────────────────────────────────────

    public void circuitBreakerTripped(String reason) {
        log.warn("[CIRCUIT BREAKER] Trading halted — {}", reason);
    }

    // ─── Bot lifecycle ────────────────────────────────────────────────────────

    public void botStopped(String triggeredBy) {
        log.warn("[BOT STOP] Emergency stop triggered by={}", triggeredBy);
        botEventService.recordBotStopped(triggeredBy);
    }

    public void botResumed(String triggeredBy) {
        log.info("[BOT RESUME] Trading resumed by={}", triggeredBy);
        botEventService.recordBotResumed(triggeredBy);
    }

    // ─── Risk events ──────────────────────────────────────────────────────────

    public void tradeRejected(String pair, String reason) {
        log.info("[TRADE REJECTED] pair={} reason={}", pair, reason);
    }

    public void dailyPnlAlert(BigDecimal dailyPnl, BigDecimal threshold) {
        log.warn("[DAILY PNL ALERT] dailyPnl={} threshold={}", dailyPnl, threshold);
    }
}
