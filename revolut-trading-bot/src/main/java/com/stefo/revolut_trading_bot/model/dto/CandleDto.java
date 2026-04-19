package com.stefo.revolut_trading_bot.model.dto;

import java.math.BigDecimal;

/** OHLCV candle for the Charts tab. {@code time} is Unix epoch seconds (lightweight-charts format). */
public record CandleDto(
        long time,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume
) {}
