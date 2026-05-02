package com.stefo.revolut_trading_bot.scheduler

import com.stefo.revolut_trading_bot.config.TradingConfig
import com.stefo.revolut_trading_bot.repository.SignalLogRepository
import spock.lang.Specification
import spock.lang.Subject

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class SignalLogCleanupSchedulerSpec extends Specification {

    SignalLogRepository repository = Mock()
    TradingConfig tradingConfig = Mock()

    @Subject
    SignalLogCleanupScheduler scheduler = new SignalLogCleanupScheduler(repository, tradingConfig)

    def "pruneOldSignalLogs deletes rows older than the configured retention window"() {
        given:
        tradingConfig.getSignalLogRetentionDays() >> 14

        when:
        scheduler.pruneOldSignalLogs()

        then:
        1 * repository.deleteOlderThan({ LocalDateTime cutoff ->
            LocalDateTime expected = LocalDateTime.now().minusDays(14)
            Math.abs(ChronoUnit.SECONDS.between(cutoff, expected)) < 5
        }) >> 12345
    }

    def "pruneOldSignalLogs honours a custom retention window"() {
        given:
        tradingConfig.getSignalLogRetentionDays() >> 7

        when:
        scheduler.pruneOldSignalLogs()

        then:
        1 * repository.deleteOlderThan({ LocalDateTime cutoff ->
            LocalDateTime expected = LocalDateTime.now().minusDays(7)
            Math.abs(ChronoUnit.SECONDS.between(cutoff, expected)) < 5
        }) >> 0
    }

    def "pruneOldSignalLogs swallows repository exceptions so the scheduler keeps running"() {
        given:
        tradingConfig.getSignalLogRetentionDays() >> 14
        repository.deleteOlderThan(_ as LocalDateTime) >> { throw new RuntimeException("db down") }

        when:
        scheduler.pruneOldSignalLogs()

        then:
        noExceptionThrown()
    }
}
