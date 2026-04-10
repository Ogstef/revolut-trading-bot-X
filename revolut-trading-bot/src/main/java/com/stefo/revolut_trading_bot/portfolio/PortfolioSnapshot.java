package com.stefo.revolut_trading_bot.portfolio;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Point-in-time snapshot of the paper portfolio.
 *
 * unrealisedPnl = how much you'd make/lose if you closed all open positions right now.
 * totalInvested  = sum of (entryPrice × quantity) for every open position.
 */
public record PortfolioSnapshot(
        String pair,
        int openPositions,
        BigDecimal totalInvested,
        BigDecimal unrealisedPnl,
        BigDecimal unrealisedPnlPct,
        BigDecimal currentPrice,
        Instant asOf
) {}
