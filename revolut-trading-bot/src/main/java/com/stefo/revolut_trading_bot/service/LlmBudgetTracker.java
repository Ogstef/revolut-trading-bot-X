package com.stefo.revolut_trading_bot.service;

import com.stefo.revolut_trading_bot.alert.AlertService;
import com.stefo.revolut_trading_bot.config.SentimentConfig;
import com.stefo.revolut_trading_bot.model.dto.BudgetAssessment;
import com.stefo.revolut_trading_bot.model.entity.LlmBudgetDaily;
import com.stefo.revolut_trading_bot.repository.LlmBudgetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Persistent budget tracker for Claude Haiku classification spend.
 *
 * Enforces two caps (both configured in {@code application.yml}):
 *   - {@code sentiment.classifier.daily-budget-usd} — soft daily cap
 *   - {@code sentiment.classifier.monthly-budget-usd} — hard monthly cap
 *
 * Budget state lives in {@code trading.llm_budget_daily} so a process restart
 * does not reset the tally. The {@link #alertedForMonth} flag is in-memory only;
 * on restart, if the current month has already crossed the alert threshold, the
 * flag is initialised to "already alerted" so we don't re-spam Telegram.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmBudgetTracker {

    private final LlmBudgetRepository repository;
    private final SentimentConfig config;
    private final AlertService alertService;

    /** UTC clock — overridable in tests. */
    private final Clock clock = Clock.systemUTC();

    /** In-memory guard so the 80% alert fires at most once per UTC month per process. */
    private volatile YearMonth alertedForMonth = null;

    /**
     * Would spending {@code usd} right now fit within both caps?
     *
     * Does NOT record the spend — only assesses. Callers must invoke
     * {@link #recordSpend(BigDecimal, int)} after the API call actually happened.
     */
    @Transactional(readOnly = true)
    public BudgetAssessment assess(BigDecimal usd) {
        BigDecimal dailySpent   = dailyTotalUsd();
        BigDecimal monthlySpent = monthlyTotalUsd();
        BigDecimal dailyCap     = config.getClassifier().getDailyBudgetUsd();
        BigDecimal monthlyCap   = config.getClassifier().getMonthlyBudgetUsd();

        BudgetAssessment.BudgetDecision decision;
        if (monthlySpent.add(usd).compareTo(monthlyCap) > 0) {
            decision = BudgetAssessment.BudgetDecision.BLOCKED_MONTHLY_CAP;
        } else if (dailySpent.add(usd).compareTo(dailyCap) > 0) {
            decision = BudgetAssessment.BudgetDecision.BLOCKED_DAILY_CAP;
        } else {
            decision = BudgetAssessment.BudgetDecision.ALLOWED;
        }

        return new BudgetAssessment(decision, dailySpent, monthlySpent, dailyCap, monthlyCap);
    }

    /**
     * Records an actually-incurred Haiku spend against today's row. Upserts so the first call
     * of the day creates the row, subsequent calls accumulate.
     *
     * Fires the one-shot 80% Telegram alert if this spend crosses the monthly threshold.
     */
    @Transactional
    public void recordSpend(BigDecimal usd, int postsClassified) {
        LocalDate today = LocalDate.now(clock);
        LlmBudgetDaily row = repository.findByDay(today)
                .orElseGet(() -> LlmBudgetDaily.builder().day(today).build());

        row.setUsdSpent(row.getUsdSpent().add(usd));
        row.setPostsClassified(row.getPostsClassified() + postsClassified);
        row.setLastUpdated(LocalDateTime.now(clock));
        repository.save(row);

        log.debug("[BUDGET] recorded {} USD, {} posts — day total {}",
                usd, postsClassified, row.getUsdSpent());

        maybeFireMonthlyAlert(monthlyTotalUsd());
    }

    @Transactional(readOnly = true)
    public BigDecimal dailyTotalUsd() {
        return repository.findByDay(LocalDate.now(clock))
                .map(LlmBudgetDaily::getUsdSpent)
                .orElse(BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public BigDecimal monthlyTotalUsd() {
        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        LocalDate monthStart   = currentMonth.atDay(1);
        LocalDate nextMonth    = currentMonth.plusMonths(1).atDay(1);
        BigDecimal total = repository.sumSpentInRange(monthStart, nextMonth);
        return total != null ? total : BigDecimal.ZERO;
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private void maybeFireMonthlyAlert(BigDecimal monthlySpent) {
        BigDecimal cap       = config.getClassifier().getMonthlyBudgetUsd();
        int        alertPct  = config.getClassifier().getAlertAtMonthlyPct();
        BigDecimal threshold = cap.multiply(BigDecimal.valueOf(alertPct))
                                  .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);

        if (monthlySpent.compareTo(threshold) < 0) return;

        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        synchronized (this) {
            if (currentMonth.equals(alertedForMonth)) return;   // already fired this month
            alertedForMonth = currentMonth;
        }
        alertService.sentimentBudgetWarning(monthlySpent, cap, alertPct);
    }
}
