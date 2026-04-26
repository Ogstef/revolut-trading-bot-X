package com.stefo.revolut_trading_bot.model.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
        LocalDateTime openedAt,
        String interval,
        String strategyName,
        String displayName
) {}
