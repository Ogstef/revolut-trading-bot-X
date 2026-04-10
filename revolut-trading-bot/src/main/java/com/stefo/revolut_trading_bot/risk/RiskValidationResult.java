package com.stefo.revolut_trading_bot.risk;

import java.math.BigDecimal;

/**
 * Result of a RiskManager validation pass.
 *
 * If approved, positionSizeEur is the calculated trade size (2% of balance).
 * If rejected, reason explains which rule was violated.
 */
public record RiskValidationResult(
        boolean approved,
        String reason,
        BigDecimal positionSizeEur   // null when rejected
) {

    public static RiskValidationResult approved(BigDecimal positionSizeEur) {
        return new RiskValidationResult(true, "All risk checks passed", positionSizeEur);
    }

    public static RiskValidationResult rejected(String reason) {
        return new RiskValidationResult(false, reason, null);
    }
}
