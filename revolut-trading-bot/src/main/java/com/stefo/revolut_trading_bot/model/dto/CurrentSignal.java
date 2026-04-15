package com.stefo.revolut_trading_bot.model.dto;

import com.stefo.revolut_trading_bot.model.enums.SignalType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CurrentSignal(
        String pair,
        String interval,
        String strategy,
        String displayName,
        SignalType signalType,
        BigDecimal confidence,
        String reason,
        BigDecimal currentPrice,
        BigDecimal emaShort,
        BigDecimal emaLong,
        BigDecimal rsi,
        LocalDateTime evaluatedAt
) {}
