package com.stefo.revolut_trading_bot.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds the bot's on/off state — the single source of truth for whether trading is active.
 *
 * The TradingLoop checks this before every cycle; POST /api/emergency-stop sets it false
 * and POST /api/resume sets it true. AtomicBoolean is thread-safe — safe to read from
 * the scheduler thread and write from an HTTP thread simultaneously.
 */
@Slf4j
@Service
public class BotStateService {

    private final AtomicBoolean active = new AtomicBoolean(true);

    public boolean isActive() {
        return active.get();
    }

    public void stop() {
        active.set(false);
        log.warn("Bot STOPPED via emergency stop. No new trades will execute until resumed.");
    }

    public void resume() {
        active.set(true);
        log.info("Bot RESUMED. Trading will continue on the next scheduled cycle.");
    }
}
