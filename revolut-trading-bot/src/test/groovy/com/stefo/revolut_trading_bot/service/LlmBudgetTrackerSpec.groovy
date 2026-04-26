package com.stefo.revolut_trading_bot.service

import com.stefo.revolut_trading_bot.alert.AlertService
import com.stefo.revolut_trading_bot.config.SentimentConfig
import com.stefo.revolut_trading_bot.model.dto.BudgetAssessment
import com.stefo.revolut_trading_bot.model.entity.LlmBudgetDaily
import com.stefo.revolut_trading_bot.repository.LlmBudgetRepository
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Optional

class LlmBudgetTrackerSpec extends Specification {

    SentimentConfig config = new SentimentConfig()
    LlmBudgetRepository repository = Mock()
    AlertService alertService = Mock()

    @Subject
    LlmBudgetTracker tracker

    def setup() {
        config.classifier.monthlyBudgetUsd  = new BigDecimal("3.00")
        config.classifier.dailyBudgetUsd    = new BigDecimal("0.12")
        config.classifier.alertAtMonthlyPct = 80
        tracker = new LlmBudgetTracker(repository, config, alertService)
    }

    def "assess returns ALLOWED when nothing spent yet"() {
        given:
        repository.findByDay(_ as LocalDate) >> Optional.empty()
        repository.sumSpentInRange(_, _) >> BigDecimal.ZERO

        expect:
        tracker.assess(new BigDecimal("0.01")).allowed()
    }

    def "assess returns BLOCKED_DAILY_CAP when daily cap would be breached"() {
        given:
        def existing = LlmBudgetDaily.builder()
                .day(LocalDate.now())
                .usdSpent(new BigDecimal("0.11"))
                .build()
        repository.findByDay(_ as LocalDate) >> Optional.of(existing)
        repository.sumSpentInRange(_, _) >> new BigDecimal("0.50")

        when:
        def result = tracker.assess(new BigDecimal("0.05"))  // 0.11 + 0.05 = 0.16 > 0.12 cap

        then:
        !result.allowed()
        result.decision() == BudgetAssessment.BudgetDecision.BLOCKED_DAILY_CAP
    }

    def "assess returns BLOCKED_MONTHLY_CAP when monthly cap would be breached"() {
        given:
        repository.findByDay(_ as LocalDate) >> Optional.empty()
        repository.sumSpentInRange(_, _) >> new BigDecimal("2.99")

        when:
        def result = tracker.assess(new BigDecimal("0.02"))

        then:
        !result.allowed()
        result.decision() == BudgetAssessment.BudgetDecision.BLOCKED_MONTHLY_CAP
    }

    def "recordSpend persists a new row if none exists"() {
        given:
        repository.findByDay(_ as LocalDate) >> Optional.empty()
        repository.sumSpentInRange(_, _) >> BigDecimal.ZERO

        when:
        tracker.recordSpend(new BigDecimal("0.005"), 10)

        then:
        1 * repository.save({ LlmBudgetDaily row ->
            row.usdSpent.compareTo(new BigDecimal("0.005")) == 0 &&
            row.postsClassified == 10
        })
    }

    def "recordSpend accumulates on an existing row"() {
        given:
        def existing = LlmBudgetDaily.builder()
                .day(LocalDate.now())
                .usdSpent(new BigDecimal("0.02"))
                .postsClassified(30)
                .build()
        repository.findByDay(_ as LocalDate) >> Optional.of(existing)
        repository.sumSpentInRange(_, _) >> new BigDecimal("0.02")

        when:
        tracker.recordSpend(new BigDecimal("0.005"), 10)

        then:
        1 * repository.save({ LlmBudgetDaily row ->
            row.usdSpent.compareTo(new BigDecimal("0.025")) == 0 &&
            row.postsClassified == 40
        })
    }

    def "fires monthly alert when crossing 80% of cap"() {
        given:
        repository.findByDay(_ as LocalDate) >> Optional.empty()
        // At recordSpend: monthlyTotalUsd is called AFTER save; return the post-save total.
        // 80% of 3.00 = 2.40. Post-save total 2.45 should trigger the alert.
        repository.sumSpentInRange(_, _) >> new BigDecimal("2.45")

        when:
        tracker.recordSpend(new BigDecimal("0.005"), 1)

        then:
        1 * alertService.sentimentBudgetWarning(new BigDecimal("2.45"), new BigDecimal("3.00"), 80)
    }

    def "alert fires at most once per process"() {
        given:
        repository.findByDay(_ as LocalDate) >> Optional.empty()
        repository.sumSpentInRange(_, _) >> new BigDecimal("2.80")

        when:
        tracker.recordSpend(new BigDecimal("0.005"), 1)
        tracker.recordSpend(new BigDecimal("0.005"), 1)

        then:
        1 * alertService.sentimentBudgetWarning(_, _, _)
    }
}
