package com.stefo.revolut_trading_bot.backtest;

import com.stefo.revolut_trading_bot.model.enums.StrategyType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Input to BacktestService.run / runWalkForward.
 *
 * paramOverrides is a free-form key/value map. Recognized keys in v1:
 *   emaShortPeriod, emaLongPeriod, rsiPeriod, rsiOverbought, rsiOversold,
 *   takeProfitPct, stopLossPct, feeRate, slippageRate, maxPositionPct.
 * Anything else is ignored (forward-compat for when more strategy params get
 * pulled from static-final into TradingConfig).
 */
public record BacktestRequest(
        String pair,
        StrategyType strategy,
        String interval,
        LocalDateTime startDate,
        LocalDateTime endDate,
        BigDecimal startingBalance,
        Map<String, Object> paramOverrides,
        String label,
        String notes
) {}
