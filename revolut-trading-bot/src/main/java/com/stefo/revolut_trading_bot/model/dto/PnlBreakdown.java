package com.stefo.revolut_trading_bot.model.dto;

import java.math.BigDecimal;

/**
 * PnL totals broken down by time period — returned by GET /api/pnl.
 * Gross fields retain original semantics; net fields deduct fees + slippage.
 */
public record PnlBreakdown(
        BigDecimal daily,
        BigDecimal weekly,
        BigDecimal monthly,
        BigDecimal allTime,
        BigDecimal dailyNet,
        BigDecimal weeklyNet,
        BigDecimal monthlyNet,
        BigDecimal allTimeNet
) {}
