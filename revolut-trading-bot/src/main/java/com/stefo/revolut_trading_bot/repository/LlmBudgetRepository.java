package com.stefo.revolut_trading_bot.repository;

import com.stefo.revolut_trading_bot.model.entity.LlmBudgetDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface LlmBudgetRepository extends JpaRepository<LlmBudgetDaily, LocalDate> {

    /**
     * Sum of {@code usd_spent} for all days in {@code [monthStart, nextMonthStart)}.
     * Returns {@link BigDecimal#ZERO} for months with no rows (COALESCE inside the query).
     */
    @Query("""
            SELECT COALESCE(SUM(b.usdSpent), 0)
              FROM LlmBudgetDaily b
             WHERE b.day >= :monthStart
               AND b.day <  :nextMonthStart
            """)
    BigDecimal sumSpentInRange(@Param("monthStart")     LocalDate monthStart,
                               @Param("nextMonthStart") LocalDate nextMonthStart);

    Optional<LlmBudgetDaily> findByDay(LocalDate day);
}
