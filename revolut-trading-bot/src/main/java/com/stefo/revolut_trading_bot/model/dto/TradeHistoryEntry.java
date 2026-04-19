package com.stefo.revolut_trading_bot.model.dto;

import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import com.stefo.revolut_trading_bot.model.enums.OrderSide;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.model.enums.TradingMode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Closed trade enriched with parent-position context — returned by
 * GET /api/strategies/{strategyType}/history.
 *
 * Carries every field of {@link Trade} plus the entry-side information that
 * normally lives on {@link Position} (signal reason, TP/SL prices, opening
 * timestamp) and two derived analytics: holding duration and R-multiple.
 */
public record TradeHistoryEntry(
        Long id,
        String pair,
        String interval,
        OrderSide side,
        BigDecimal entryPrice,
        BigDecimal exitPrice,
        BigDecimal quantity,
        BigDecimal pnl,
        BigDecimal pnlPct,
        StrategyType strategyName,
        String exitReason,
        TradingMode tradingMode,
        LocalDateTime executedAt,
        LocalDateTime closedAt,
        // ── enrichment from Position ──
        String entrySignalReason,
        BigDecimal takeProfit,
        BigDecimal stopLoss,
        LocalDateTime openedAt,
        // ── derived analytics ──
        Long holdingDurationSeconds,
        BigDecimal rMultiple,
        // ── cost fields ──
        BigDecimal entryFee,
        BigDecimal exitFee,
        BigDecimal entrySlippage,
        BigDecimal exitSlippage,
        BigDecimal netPnl,
        BigDecimal netPnlPct
) {

    public static TradeHistoryEntry from(Trade trade) {
        Position position = trade.getPosition();

        String entrySignalReason = position != null ? position.getSignalReason() : null;
        BigDecimal takeProfit    = position != null ? position.getTakeProfit()   : null;
        BigDecimal stopLoss      = position != null ? position.getStopLoss()     : null;
        LocalDateTime openedAt   = position != null ? position.getOpenedAt()     : null;

        Long holdingDurationSeconds = null;
        if (openedAt != null && trade.getClosedAt() != null) {
            holdingDurationSeconds = Duration.between(openedAt, trade.getClosedAt()).getSeconds();
        }

        BigDecimal rMultiple = computeRMultiple(
                trade.getPnl(), trade.getEntryPrice(), stopLoss, trade.getQuantity());

        return new TradeHistoryEntry(
                trade.getId(),
                trade.getPair(),
                trade.getInterval(),
                trade.getSide(),
                trade.getEntryPrice(),
                trade.getExitPrice(),
                trade.getQuantity(),
                trade.getPnl(),
                trade.getPnlPct(),
                trade.getStrategyName(),
                trade.getExitReason(),
                trade.getTradingMode(),
                trade.getExecutedAt(),
                trade.getClosedAt(),
                entrySignalReason,
                takeProfit,
                stopLoss,
                openedAt,
                holdingDurationSeconds,
                rMultiple,
                trade.getEntryFee(),
                trade.getExitFee(),
                trade.getEntrySlippage(),
                trade.getExitSlippage(),
                trade.getNetPnl(),
                trade.getNetPnlPct()
        );
    }

    /**
     * R-multiple = realised PnL divided by initial risk (|entry − stopLoss| × quantity).
     * Returns null when any input is missing or risk is zero.
     */
    private static BigDecimal computeRMultiple(BigDecimal pnl,
                                               BigDecimal entryPrice,
                                               BigDecimal stopLoss,
                                               BigDecimal quantity) {
        if (pnl == null || entryPrice == null || stopLoss == null || quantity == null) {
            return null;
        }
        BigDecimal riskPerUnit = entryPrice.subtract(stopLoss).abs();
        BigDecimal totalRisk   = riskPerUnit.multiply(quantity);
        if (totalRisk.signum() == 0) {
            return null;
        }
        return pnl.divide(totalRisk, 4, RoundingMode.HALF_UP);
    }
}
