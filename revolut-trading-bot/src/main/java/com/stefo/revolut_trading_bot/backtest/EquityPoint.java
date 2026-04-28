package com.stefo.revolut_trading_bot.backtest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One sample on the equity curve. equity = balance + unrealizedPnl.
 * drawdown is in EUR (peak − equity); drawdownPct is the same as a fraction of peak.
 */
public record EquityPoint(
        LocalDateTime timestamp,
        BigDecimal equity,
        BigDecimal drawdown,
        BigDecimal drawdownPct
) {}
