package com.stefo.revolut_trading_bot.portfolio;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.enums.OrderSide;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
import com.stefo.revolut_trading_bot.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * Provides a live view of the paper portfolio.
 *
 * Takes the current market price as a parameter — it does NOT call the API itself.
 * The caller (TradingLoop or test endpoint) is responsible for supplying the price,
 * which keeps this service testable and decoupled from the HTTP layer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PortfolioService {

    private final PositionRepository positionRepository;
    private final TradingConfig config;

    /**
     * Builds a snapshot of the current portfolio state.
     *
     * @param currentPrice live BTC-EUR price used to calculate unrealised PnL
     */
    public PortfolioSnapshot getSnapshot(BigDecimal currentPrice) {
        List<Position> open = positionRepository.findByStatus(OrderStatus.OPEN);

        BigDecimal totalInvested  = BigDecimal.ZERO;
        BigDecimal unrealisedPnl  = BigDecimal.ZERO;

        for (Position p : open) {
            BigDecimal invested = p.getEntryPrice().multiply(p.getQuantity());
            BigDecimal pnl      = calcUnrealisedPnl(p, currentPrice);
            totalInvested  = totalInvested.add(invested);
            unrealisedPnl  = unrealisedPnl.add(pnl);
        }

        BigDecimal unrealisedPnlPct = totalInvested.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : unrealisedPnl.divide(totalInvested, 8, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(4, RoundingMode.HALF_UP);

        log.debug("Portfolio snapshot — openPositions={} invested={} unrealisedPnl={}",
                open.size(), totalInvested, unrealisedPnl);

        return new PortfolioSnapshot(
                config.getPair(),
                open.size(),
                totalInvested.setScale(2, RoundingMode.HALF_UP),
                unrealisedPnl.setScale(2, RoundingMode.HALF_UP),
                unrealisedPnlPct,
                currentPrice,
                Instant.now()
        );
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private BigDecimal calcUnrealisedPnl(Position position, BigDecimal currentPrice) {
        BigDecimal priceDiff = currentPrice.subtract(position.getEntryPrice());
        if (position.getSide() == OrderSide.SELL) {
            priceDiff = priceDiff.negate();
        }
        return priceDiff.multiply(position.getQuantity()).setScale(8, RoundingMode.HALF_UP);
    }
}
