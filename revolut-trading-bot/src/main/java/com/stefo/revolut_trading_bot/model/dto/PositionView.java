package com.stefo.revolut_trading_bot.model.dto;

import com.stefo.revolut_trading_bot.model.enums.TradingVehicle;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Open position enriched with live unrealised PnL — returned by GET /api/positions.
 *
 * Unlike the raw Position entity, this includes the calculated unrealised figures
 * at the current market price plus the leverage dimension (vehicle / collateral /
 * notional / liquidationPrice / currentMarginRatio / fundingFeesAccrued) so the
 * caller doesn't have to compute any of it.
 *
 * For SPOT: vehicle=SPOT, leverage=1, collateral/notional/liquidationPrice/currentMarginRatio null.
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
        LocalDateTime openedAt,
        String interval,
        String strategyName,
        String displayName,
        TradingVehicle vehicle,
        int leverage,
        BigDecimal collateral,
        BigDecimal notional,
        BigDecimal liquidationPrice,
        BigDecimal currentMarginRatio,
        BigDecimal fundingFeesAccrued
) {}
