package com.stefo.revolut_trading_bot.scheduler;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.repository.SignalLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Nightly job that prunes old rows from {@code trading.signal_logs} so the table stays bounded.
 * Retention window is configured via {@code trading.signal-log-retention-days} (default 14).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalLogCleanupScheduler {

    private final SignalLogRepository signalLogRepository;
    private final TradingConfig tradingConfig;

    /** Runs every day at 04:00 server time — quiet window, separate from the 21:00 daily summary. */
    @Scheduled(cron = "0 0 4 * * *")
    public void pruneOldSignalLogs() {
        int retentionDays = tradingConfig.getSignalLogRetentionDays();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        try {
            int deleted = signalLogRepository.deleteOlderThan(cutoff);
            log.info("signal_logs cleanup — deleted {} rows older than {} ({} day retention)",
                    deleted, cutoff, retentionDays);
        } catch (Exception e) {
            log.warn("signal_logs cleanup failed: {}", e.getMessage(), e);
        }
    }
}
