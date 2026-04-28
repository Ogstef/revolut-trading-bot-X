package com.stefo.revolut_trading_bot.backtest;

import com.stefo.revolut_trading_bot.model.enums.OrderSide;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Single closed trade produced by a backtest replay. Mirrors the live Trade
 * entity's relevant fields but is NEVER persisted to trading.trades — it
 * lives inside the BacktestRun's `trades` JSONB blob.
 */
public record SimulatedTrade(
        int sequence,
        OrderSide side,
        BigDecimal entryPrice,
        BigDecimal exitPrice,
        BigDecimal quantity,
        LocalDateTime executedAt,
        LocalDateTime closedAt,
        BigDecimal pnl,             // gross
        BigDecimal pnlPct,
        BigDecimal entryFee,
        BigDecimal exitFee,
        BigDecimal entrySlippage,
        BigDecimal exitSlippage,
        BigDecimal netPnl,
        BigDecimal netPnlPct,
        String exitReason,          // TP_HIT / SL_HIT / SIGNAL_EXIT / BACKTEST_END
        String entrySignalReason
) {}
