package com.stefo.revolut_trading_bot.execution;

import com.stefo.revolut_trading_bot.alert.AlertService;
import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import com.stefo.revolut_trading_bot.model.enums.OrderSide;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
import com.stefo.revolut_trading_bot.model.enums.TradingMode;
import com.stefo.revolut_trading_bot.repository.PositionRepository;
import com.stefo.revolut_trading_bot.repository.TradeRepository;
import com.stefo.revolut_trading_bot.risk.TakeProfitStopLossManager;
import com.stefo.revolut_trading_bot.strategy.Signal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Simulates order execution in PAPER mode — no real API orders are placed.
 *
 * Trade lifecycle:
 *   openPosition()  → creates a Position (OPEN) + a Trade (exitPrice=null)
 *   closePosition() → marks Position as CLOSED + fills in exitPrice, PnL on the Trade
 *
 * PnL is calculated at close time using the current market price.
 * All amounts use BigDecimal — no floating point.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperTradingService {

    private final PositionRepository positionRepository;
    private final TradeRepository tradeRepository;
    private final TakeProfitStopLossManager tpslManager;
    private final TradingConfig config;
    private final AlertService alertService;

    /**
     * Simulates opening a new position.
     *
     * @param signal          the signal that triggered this trade
     * @param positionSizeEur how much EUR to deploy (from RiskManager)
     * @param currentPrice    current BTC-EUR price (used as simulated fill price)
     * @return the persisted open Position
     */
    @Transactional
    public Position openPosition(Signal signal, BigDecimal positionSizeEur, BigDecimal currentPrice) {
        OrderSide side = signal.type().name().equals("BUY") ? OrderSide.BUY : OrderSide.SELL;

        // How much BTC we can buy with positionSizeEur at the current price
        BigDecimal quantity = positionSizeEur.divide(currentPrice, 8, RoundingMode.HALF_UP);

        BigDecimal takeProfit = tpslManager.calculateTakeProfit(currentPrice);
        BigDecimal stopLoss   = tpslManager.calculateStopLoss(currentPrice);

        Position position = Position.builder()
                .pair(signal.pair())
                .side(side)
                .entryPrice(currentPrice)
                .quantity(quantity)
                .takeProfit(takeProfit)
                .stopLoss(stopLoss)
                .status(OrderStatus.OPEN)
                .strategyName(signal.strategyType())
                .signalReason(signal.reason())
                .build();
        positionRepository.save(position);

        // Open trade record — exitPrice / pnl filled in when position closes
        Trade trade = Trade.builder()
                .position(position)
                .pair(signal.pair())
                .side(side)
                .entryPrice(currentPrice)
                .quantity(quantity)
                .strategyName(signal.strategyType())
                .tradingMode(TradingMode.PAPER)
                .build();
        tradeRepository.save(trade);

        log.info("[PAPER] Opened {} position id={} — entry={} qty={} tp={} sl={}",
                side, position.getId(), currentPrice, quantity, takeProfit, stopLoss);
        alertService.positionOpened(position);
        return position;
    }

    /**
     * Simulates closing an existing position.
     *
     * @param position     the open position to close
     * @param currentPrice current BTC-EUR price (simulated fill price)
     * @param exitReason   one of: TP_HIT, SL_HIT, SIGNAL_EXIT, MANUAL
     * @return the completed Trade record with PnL filled in
     */
    @Transactional
    public Trade closePosition(Position position, BigDecimal currentPrice, String exitReason) {
        Trade trade = tradeRepository.findOpenTradeByPosition(position)
                .orElseThrow(() -> new IllegalStateException(
                        "No open trade found for position id=" + position.getId()));

        BigDecimal pnl    = calculatePnl(position, currentPrice);
        BigDecimal pnlPct = calculatePnlPct(position, pnl);

        trade.setExitPrice(currentPrice);
        trade.setPnl(pnl);
        trade.setPnlPct(pnlPct);
        trade.setExitReason(exitReason);
        trade.setClosedAt(LocalDateTime.now());
        tradeRepository.save(trade);

        position.setStatus(OrderStatus.CLOSED);
        position.setClosedAt(LocalDateTime.now());
        positionRepository.save(position);

        log.info("[PAPER] Closed {} position id={} — exit={} pnl={} pnlPct={}% reason={}",
                position.getSide(), position.getId(), currentPrice, pnl, pnlPct, exitReason);
        alertService.positionClosed(position, trade);
        return trade;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private BigDecimal calculatePnl(Position position, BigDecimal exitPrice) {
        BigDecimal priceDiff = exitPrice.subtract(position.getEntryPrice());
        // For SELL (short), profit is reversed
        if (position.getSide() == OrderSide.SELL) {
            priceDiff = priceDiff.negate();
        }
        return priceDiff.multiply(position.getQuantity()).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal calculatePnlPct(Position position, BigDecimal pnl) {
        BigDecimal invested = position.getEntryPrice().multiply(position.getQuantity());
        if (invested.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return pnl.divide(invested, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
