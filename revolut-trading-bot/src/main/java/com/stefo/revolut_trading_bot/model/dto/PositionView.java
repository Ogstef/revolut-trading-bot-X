package com.stefo.revolut_trading_bot.model.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Open position enriched with live unrealised PnL — returned by GET /api/positions.
 *
 * Unlike the raw Position entity, this includes the calculated unrealised figures
 * at the current market price so the caller doesn't have to compute them.
 */
public record PositionView(
        Long id,
        String pair,
        String side,
        BigDecimal entryPrice,
        BigDecimal quantity,
        BigDecimal takeProfit,
        BigDecimal stopLoss,
        BigDecimal currentPrice,
        BigDecimal unrealisedPnl,
        BigDecimal unrealisedPnlPct,
        String signalReason,
        LocalDateTime openedAt
) {}
