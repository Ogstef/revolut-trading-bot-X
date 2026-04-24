package com.stefo.revolut_trading_bot.execution;

import com.stefo.revolut_trading_bot.alert.AlertService;
import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import com.stefo.revolut_trading_bot.model.enums.OrderSide;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
import com.stefo.revolut_trading_bot.model.enums.TradingMode;
import com.stefo.revolut_trading_bot.model.enums.TradingVehicle;
import com.stefo.revolut_trading_bot.portfolio.VehicleBalanceService;
import com.stefo.revolut_trading_bot.repository.PositionRepository;
import com.stefo.revolut_trading_bot.repository.TradeRepository;
import com.stefo.revolut_trading_bot.risk.TakeProfitStopLossManager;
import com.stefo.revolut_trading_bot.service.BotEventService;
import com.stefo.revolut_trading_bot.strategy.Signal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Paper-trading simulator for LEVERAGED vehicles (LEV_3X / LEV_5X / LEV_10X).
 *
 * Sibling to PaperTradingService — NOT a subclass — because the mechanics diverge:
 *   - notional = collateral × leverage (quantity is based on notional, not position size)
 *   - fees/slippage charged on notional (already the case for spot, kept identical here)
 *   - liquidation price computed at entry and enforced by TakeProfitStopLossManager
 *   - funding fees accrue per cycle while the position is open
 *   - collateral is debited at open and (collateral + netPnl) is credited at close,
 *     via VehicleBalanceService
 *
 * TP/SL percentages are the same as spot — leverage amplifies the realised EUR PnL
 * automatically through the larger quantity, so no separate TP/SL math is required.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeveragedPaperTradingService {

    private static final long FUNDING_PERIOD_SECONDS = Duration.ofHours(8).toSeconds();

    private final PositionRepository       positionRepository;
    private final TradeRepository          tradeRepository;
    private final TakeProfitStopLossManager tpslManager;
    private final VehicleBalanceService    vehicleBalanceService;
    private final LeverageCooldownService  cooldownService;
    private final TradingConfig            config;
    private final AlertService             alertService;
    private final BotEventService          botEventService;

    /**
     * Opens a leveraged position.
     *
     * @param signal       BUY → long, SELL → short (when allow-shorts is enabled upstream)
     * @param collateral   EUR to lock up as margin for this trade
     * @param currentPrice simulated fill price
     * @param vehicle      LEV_3X / LEV_5X / LEV_10X — must be leveraged (leverage > 1)
     */
    @Transactional
    public Position openPosition(Signal signal,
                                 BigDecimal collateral,
                                 BigDecimal currentPrice,
                                 TradingVehicle vehicle) {
        if (!vehicle.isLeveraged()) {
            throw new IllegalArgumentException(
                    "LeveragedPaperTradingService cannot open SPOT positions — use PaperTradingService");
        }
        OrderSide side = signal.type().name().equals("BUY") ? OrderSide.BUY : OrderSide.SELL;
        int leverage = vehicle.getLeverage();

        BigDecimal notional  = collateral.multiply(BigDecimal.valueOf(leverage))
                                         .setScale(8, RoundingMode.HALF_UP);
        BigDecimal quantity  = notional.divide(currentPrice, 8, RoundingMode.HALF_UP);
        BigDecimal entryFee  = notional.multiply(config.getCosts().getFeeRate())
                                       .setScale(8, RoundingMode.HALF_UP);
        BigDecimal entrySlip = notional.multiply(config.getCosts().getSlippageRate())
                                       .setScale(8, RoundingMode.HALF_UP);

        BigDecimal takeProfit = tpslManager.calculateTakeProfit(currentPrice);
        BigDecimal stopLoss   = tpslManager.calculateStopLoss(currentPrice);
        // For shorts, TP is below entry and SL above — reuse spot math inverted
        if (side == OrderSide.SELL) {
            takeProfit = currentPrice.multiply(BigDecimal.ONE
                    .subtract(config.getRisk().getTakeProfitPct()
                            .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)))
                    .setScale(8, RoundingMode.HALF_UP);
            stopLoss = currentPrice.multiply(BigDecimal.ONE
                    .add(config.getRisk().getStopLossPct()
                            .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)))
                    .setScale(8, RoundingMode.HALF_UP);
        }

        BigDecimal liquidationPrice = tpslManager.calculateLiquidationPrice(
                currentPrice, side, leverage, config.getLeverage().getMaintenanceMarginPct());

        LocalDateTime now = LocalDateTime.now();
        Position position = Position.builder()
                .pair(signal.pair())
                .interval(signal.interval())
                .side(side)
                .entryPrice(currentPrice)
                .quantity(quantity)
                .takeProfit(takeProfit)
                .stopLoss(stopLoss)
                .status(OrderStatus.OPEN)
                .strategyName(signal.strategyType())
                .signalReason(signal.reason())
                .vehicle(vehicle)
                .leverage((short) leverage)
                .collateral(collateral)
                .notional(notional)
                .liquidationPrice(liquidationPrice)
                .fundingFeesAccrued(BigDecimal.ZERO)
                .lastFundingAt(now)
                .build();
        positionRepository.save(position);

        Trade trade = Trade.builder()
                .position(position)
                .pair(signal.pair())
                .interval(signal.interval())
                .side(side)
                .entryPrice(currentPrice)
                .quantity(quantity)
                .strategyName(signal.strategyType())
                .tradingMode(TradingMode.PAPER)
                .vehicle(vehicle)
                .leverage((short) leverage)
                .collateral(collateral)
                .entryFee(entryFee)
                .entrySlippage(entrySlip)
                .build();
        tradeRepository.save(trade);

        vehicleBalanceService.debit(
                signal.pair(), signal.strategyType(), vehicle, collateral);

        log.info("[LEV] Opened {} {} position id={} — entry={} qty={} collateral={} notional={} liq={} tp={} sl={}",
                vehicle, side, position.getId(), currentPrice, quantity, collateral, notional,
                liquidationPrice, takeProfit, stopLoss);
        alertService.positionOpened(position);
        return position;
    }

    /**
     * Closes a leveraged position — identical gross PnL math to spot, plus funding-fee
     * deduction and collateral return to the ledger.
     */
    @Transactional
    public Trade closePosition(Position position, BigDecimal currentPrice, String exitReason) {
        Trade trade = tradeRepository.findOpenTradeByPosition(position)
                .orElseThrow(() -> new IllegalStateException(
                        "No open trade found for position id=" + position.getId()));

        BigDecimal pnl    = calculatePnl(position, currentPrice);
        BigDecimal pnlPct = calculatePnlPct(position.getCollateral(), pnl);

        BigDecimal exitNotional = currentPrice.multiply(position.getQuantity())
                                              .setScale(8, RoundingMode.HALF_UP);
        BigDecimal exitFee      = exitNotional.multiply(config.getCosts().getFeeRate())
                                              .setScale(8, RoundingMode.HALF_UP);
        BigDecimal exitSlippage = exitNotional.multiply(config.getCosts().getSlippageRate())
                                              .setScale(8, RoundingMode.HALF_UP);

        BigDecimal fundingFees = position.getFundingFeesAccrued() == null
                ? BigDecimal.ZERO : position.getFundingFeesAccrued();
        BigDecimal totalCosts  = trade.getEntryFee().add(trade.getEntrySlippage())
                                      .add(exitFee).add(exitSlippage).add(fundingFees);
        BigDecimal netPnl      = pnl.subtract(totalCosts).setScale(8, RoundingMode.HALF_UP);
        BigDecimal netPnlPct   = calculatePnlPct(position.getCollateral(), netPnl);

        boolean liquidated = TakeProfitStopLossManager.EXIT_LIQUIDATED.equals(exitReason);

        trade.setExitPrice(currentPrice);
        trade.setPnl(pnl);
        trade.setPnlPct(pnlPct);
        trade.setExitFee(exitFee);
        trade.setExitSlippage(exitSlippage);
        trade.setFundingFees(fundingFees);
        trade.setNetPnl(netPnl);
        trade.setNetPnlPct(netPnlPct);
        trade.setExitReason(exitReason);
        trade.setLiquidated(liquidated);
        trade.setClosedAt(LocalDateTime.now());
        tradeRepository.save(trade);

        position.setStatus(OrderStatus.CLOSED);
        position.setClosedAt(LocalDateTime.now());
        positionRepository.save(position);

        // Return surviving equity to the ledger. For a liquidation netPnl is typically
        // near -collateral so this floors at zero — no negative balances.
        BigDecimal returned = position.getCollateral().add(netPnl).max(BigDecimal.ZERO);
        vehicleBalanceService.credit(
                position.getPair(), position.getStrategyName(), position.getVehicle(), returned);
        cooldownService.recordClose(position);

        log.info("[LEV] Closed {} {} position id={} — exit={} gross={} funding={} net={} reason={}{}",
                position.getVehicle(), position.getSide(), position.getId(), currentPrice, pnl,
                fundingFees, netPnl, exitReason, liquidated ? " [LIQUIDATED]" : "");

        if (liquidated) {
            botEventService.recordPositionLiquidated(trade);
        }
        alertService.positionClosed(position, trade);
        return trade;
    }

    /**
     * Accrues funding for an open leveraged position.
     *
     * Flat-rate perpetual-style funding: (elapsed / 8h) × fundingRatePer8h × notional.
     * Called per cycle from TradingLoop while leverage.enabled is true.
     */
    @Transactional
    public void accrueFunding(Position position, LocalDateTime now) {
        if (position.getLeverage() <= 1 || position.getNotional() == null) return;
        LocalDateTime lastAt = position.getLastFundingAt() != null
                ? position.getLastFundingAt() : position.getOpenedAt();
        long elapsed = Duration.between(lastAt, now).getSeconds();
        if (elapsed <= 0) return;

        BigDecimal fraction = BigDecimal.valueOf(elapsed)
                .divide(BigDecimal.valueOf(FUNDING_PERIOD_SECONDS), 10, RoundingMode.HALF_UP);
        BigDecimal delta = position.getNotional()
                .multiply(config.getLeverage().getFundingRatePer8h())
                .multiply(fraction)
                .setScale(8, RoundingMode.HALF_UP);

        BigDecimal previous = position.getFundingFeesAccrued() == null
                ? BigDecimal.ZERO : position.getFundingFeesAccrued();
        position.setFundingFeesAccrued(previous.add(delta));
        position.setLastFundingAt(now);
        positionRepository.save(position);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private BigDecimal calculatePnl(Position position, BigDecimal exitPrice) {
        BigDecimal priceDiff = exitPrice.subtract(position.getEntryPrice());
        if (position.getSide() == OrderSide.SELL) {
            priceDiff = priceDiff.negate();
        }
        return priceDiff.multiply(position.getQuantity()).setScale(8, RoundingMode.HALF_UP);
    }

    /** PnL as a percent of COLLATERAL (the money actually at risk), not notional. */
    private BigDecimal calculatePnlPct(BigDecimal collateral, BigDecimal pnl) {
        if (collateral == null || collateral.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return pnl.divide(collateral, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
