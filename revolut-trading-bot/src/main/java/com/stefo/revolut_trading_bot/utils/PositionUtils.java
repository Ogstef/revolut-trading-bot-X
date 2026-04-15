package com.stefo.revolut_trading_bot.utils;

import com.stefo.revolut_trading_bot.model.dto.PositionView;
import com.stefo.revolut_trading_bot.model.entity.Position;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.math.RoundingMode;

@UtilityClass
public class PositionUtils {

    public static PositionView toPositionView(Position p, BigDecimal currentPrice) {
        // Unrealised PnL = (currentPrice - entryPrice) × quantity
        // For SELL (short): reversed
        BigDecimal priceDiff = currentPrice.subtract(p.getEntryPrice());
        if (p.getSide().name().equals("SELL")) {
            priceDiff = priceDiff.negate();
        }
        BigDecimal unrealisedPnl = priceDiff.multiply(p.getQuantity()).setScale(2, RoundingMode.HALF_UP);

        BigDecimal invested = p.getEntryPrice().multiply(p.getQuantity());
        BigDecimal unrealisedPnlPct = invested.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : unrealisedPnl.divide(invested, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        return new PositionView(
                p.getId(), p.getPair(), p.getSide().name(),
                p.getEntryPrice(), p.getQuantity(),
                p.getTakeProfit(), p.getStopLoss(),
                currentPrice, unrealisedPnl, unrealisedPnlPct,
                p.getSignalReason(), p.getOpenedAt(),
                p.getInterval(),
                p.getStrategyName().name(),
                p.getStrategyName().getDisplayName()
        );
    }

}
