package com.stefo.revolut_trading_bot.backtest;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.entity.BacktestRun;
import com.stefo.revolut_trading_bot.model.entity.Candlestick;
import com.stefo.revolut_trading_bot.model.enums.OrderSide;
import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.repository.BacktestRunRepository;
import com.stefo.revolut_trading_bot.repository.CandlestickRepository;
import com.stefo.revolut_trading_bot.strategy.Signal;
import com.stefo.revolut_trading_bot.strategy.TradingStrategy;
import com.stefo.revolut_trading_bot.strategy.impl.EmaCrossoverStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.num.DecimalNum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Replays a single (pair, strategy, interval) over historical candles and
 * records every simulated trade + a per-bar equity curve. Reuses the live
 * strategy {@code evaluate()} code paths so backtest results stay tightly
 * aligned with what production would have done.
 *
 * Cost model mirrors {@link com.stefo.revolut_trading_bot.execution.PaperTradingService}
 * exactly (same fee + slippage formulas) so net P&L is comparable to live.
 *
 * Param overrides flow through a deep-copied {@link TradingConfig} snapshot —
 * the live singleton is never mutated. For {@code EMA_CROSSOVER}, a new
 * {@link EmaCrossoverStrategy} is instantiated with the snapshot. Other
 * strategies have hardcoded constants (Bollinger period, MACD 12/26/9, etc.) —
 * they backtest at default values until a separate refactor exposes them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private final CandlestickRepository  candlestickRepository;
    private final BacktestRunRepository  backtestRunRepository;
    private final TradingConfig          tradingConfig;
    private final List<TradingStrategy>  strategies;

    @Transactional
    public BacktestRunDetail run(BacktestRequest req) {
        validate(req);

        TradingConfig override = applyOverrides(tradingConfig, req.paramOverrides());
        BigDecimal startBalance = resolveStartingBalance(req, override);

        List<Candlestick> all = candlestickRepository
                .findByPairAndIntervalOrderByTimestampAsc(req.pair(), req.interval());
        List<Candlestick> windowed = all.stream()
                .filter(c -> !c.getTimestamp().isBefore(req.startDate()))
                .filter(c -> !c.getTimestamp().isAfter(req.endDate()))
                .toList();

        if (windowed.size() < 30) {
            throw new IllegalArgumentException(
                    "Insufficient candles for backtest window: " + windowed.size()
                  + " bars available for " + req.pair() + "/" + req.interval()
                  + " between " + req.startDate() + " and " + req.endDate()
                  + ". Need at least 30 to evaluate a strategy.");
        }

        TradingStrategy strategy = resolveStrategy(req.strategy(), override);
        Duration barDuration = Duration.ofMinutes(intervalToMinutes(req.interval()));
        BarSeries series = buildBarSeries(req.pair(), req.interval(), barDuration, windowed);

        SimulationResult sim = walkForward(series, strategy, req.pair(), req.interval(),
                                           override, startBalance);

        BacktestStats stats = BacktestStatsCalculator.compute(
                sim.trades, sim.equity, req.startDate(), req.endDate());

        BacktestRun saved = backtestRunRepository.save(BacktestRun.builder()
                .id(UUID.randomUUID())
                .pair(req.pair())
                .strategy(req.strategy())
                .interval(req.interval())
                .startDate(req.startDate())
                .endDate(req.endDate())
                .startingBalance(startBalance)
                .params(req.paramOverrides() == null ? Map.of() : req.paramOverrides())
                .stats(stats)
                .trades(sim.trades)
                .equityCurve(sim.equity)
                .label(req.label() != null ? req.label() : autoLabel(req))
                .notes(req.notes())
                .createdAt(LocalDateTime.now())
                .build());

        log.info("Backtest {} {} {} {} → {} trades, net {}, sharpe {}",
                saved.getId(), req.pair(), req.strategy(), req.interval(),
                stats.totalTrades(), stats.netPnl(), stats.sharpeRatio());

        return toDetail(saved);
    }

    @Transactional
    public WalkForwardResult runWalkForward(BacktestRequest req, int windows) {
        if (windows < 2 || windows > 10) {
            throw new IllegalArgumentException("windows must be in [2, 10], got " + windows);
        }
        Duration total = Duration.between(req.startDate(), req.endDate());
        if (total.isNegative() || total.isZero()) {
            throw new IllegalArgumentException("end_date must be after start_date");
        }
        long perWindowSeconds = total.toSeconds() / windows;

        List<BacktestRunSummary> summaries = new ArrayList<>();
        for (int i = 0; i < windows; i++) {
            LocalDateTime ws = req.startDate().plusSeconds(perWindowSeconds * i);
            LocalDateTime we = (i == windows - 1)
                    ? req.endDate()
                    : req.startDate().plusSeconds(perWindowSeconds * (i + 1));
            BacktestRequest sub = new BacktestRequest(
                    req.pair(), req.strategy(), req.interval(),
                    ws, we, req.startingBalance(), req.paramOverrides(),
                    "walk-forward " + (i + 1) + "/" + windows + " — " + safeLabel(req),
                    req.notes());
            try {
                BacktestRunDetail detail = run(sub);
                summaries.add(toSummary(detail));
            } catch (IllegalArgumentException e) {
                log.warn("Walk-forward window {}/{} skipped: {}", i + 1, windows, e.getMessage());
            }
        }

        return new WalkForwardResult(summaries, computeVarianceMetrics(summaries));
    }

    // ─── Single-pair walk forward ─────────────────────────────────────────────

    private record OpenPos(
            BigDecimal entryPrice, BigDecimal quantity,
            BigDecimal takeProfit, BigDecimal stopLoss,
            BigDecimal entryFee, BigDecimal entrySlippage,
            LocalDateTime openedAt, String entryReason
    ) {}

    private record SimulationResult(List<SimulatedTrade> trades, List<EquityPoint> equity) {}

    private SimulationResult walkForward(BarSeries series, TradingStrategy strategy,
                                         String pair, String interval,
                                         TradingConfig override, BigDecimal startBalance) {
        List<SimulatedTrade> trades = new ArrayList<>();
        List<EquityPoint> equity = new ArrayList<>();
        OpenPos open = null;
        BigDecimal balance = startBalance;
        BigDecimal peakEquity = startBalance;
        int sequence = 0;

        BigDecimal feeRate    = override.getCosts().getFeeRate();
        BigDecimal slipRate   = override.getCosts().getSlippageRate();
        BigDecimal tpPct      = override.getRisk().getTakeProfitPct();
        BigDecimal slPct      = override.getRisk().getStopLossPct();
        BigDecimal posPct     = override.getRisk().getMaxPositionPct();

        int barCount = series.getBarCount();

        for (int i = 0; i < barCount; i++) {
            org.ta4j.core.Bar bar = series.getBar(i);
            BigDecimal close = bd(bar.getClosePrice().doubleValue());
            LocalDateTime barTime = bar.getEndTime().toLocalDateTime();

            // 1) Check TP/SL first if a position is open
            if (open != null) {
                BigDecimal exitPx = null;
                String exitReason = null;
                if (close.compareTo(open.takeProfit()) >= 0) {
                    exitPx = open.takeProfit();
                    exitReason = "TP_HIT";
                } else if (close.compareTo(open.stopLoss()) <= 0) {
                    exitPx = open.stopLoss();
                    exitReason = "SL_HIT";
                }
                if (exitPx != null) {
                    SimulatedTrade t = closeTrade(open, exitPx, exitReason, barTime,
                                                  feeRate, slipRate, ++sequence);
                    trades.add(t);
                    balance = balance.add(t.netPnl());
                    open = null;
                }
            }

            // 2) Evaluate strategy on the slice [0..i]
            BarSeries sub = series.getSubSeries(0, i + 1);
            Signal signal = strategy.evaluate(sub, pair).withInterval(interval);

            // 3) Act on signal
            if (signal.type() == SignalType.BUY && open == null) {
                BigDecimal notional = balance.multiply(posPct).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
                BigDecimal qty = notional.divide(close, 8, RoundingMode.HALF_UP);
                if (qty.signum() > 0) {
                    BigDecimal entryFee  = notional.multiply(feeRate).setScale(8, RoundingMode.HALF_UP);
                    BigDecimal entrySlip = notional.multiply(slipRate).setScale(8, RoundingMode.HALF_UP);
                    BigDecimal tp = close.multiply(BigDecimal.ONE.add(tpPct.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)));
                    BigDecimal sl = close.multiply(BigDecimal.ONE.subtract(slPct.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)));
                    open = new OpenPos(close, qty, tp, sl, entryFee, entrySlip, barTime, signal.reason());
                }
            } else if (signal.type() == SignalType.SELL && open != null) {
                SimulatedTrade t = closeTrade(open, close, "SIGNAL_EXIT", barTime,
                                              feeRate, slipRate, ++sequence);
                trades.add(t);
                balance = balance.add(t.netPnl());
                open = null;
            }

            // 4) Equity sample
            BigDecimal unreal = (open == null) ? BigDecimal.ZERO
                    : close.subtract(open.entryPrice()).multiply(open.quantity());
            BigDecimal equityNow = balance.add(unreal);
            if (equityNow.compareTo(peakEquity) > 0) peakEquity = equityNow;
            BigDecimal drawdown = peakEquity.subtract(equityNow);
            BigDecimal drawdownPct = peakEquity.signum() == 0 ? BigDecimal.ZERO
                    : drawdown.divide(peakEquity, 8, RoundingMode.HALF_UP);
            equity.add(new EquityPoint(barTime, equityNow.setScale(8, RoundingMode.HALF_UP),
                                       drawdown.setScale(8, RoundingMode.HALF_UP),
                                       drawdownPct.setScale(8, RoundingMode.HALF_UP)));
        }

        // Force-close at the last bar if anything remains open
        if (open != null) {
            org.ta4j.core.Bar last = series.getBar(barCount - 1);
            BigDecimal lastClose = bd(last.getClosePrice().doubleValue());
            SimulatedTrade t = closeTrade(open, lastClose, "BACKTEST_END",
                                          last.getEndTime().toLocalDateTime(),
                                          feeRate, slipRate, ++sequence);
            trades.add(t);
        }

        return new SimulationResult(trades, equity);
    }

    private SimulatedTrade closeTrade(OpenPos open, BigDecimal exitPrice, String reason,
                                       LocalDateTime closedAt,
                                       BigDecimal feeRate, BigDecimal slipRate, int seq) {
        BigDecimal exitNotional = exitPrice.multiply(open.quantity());
        BigDecimal exitFee = exitNotional.multiply(feeRate).setScale(8, RoundingMode.HALF_UP);
        BigDecimal exitSlip = exitNotional.multiply(slipRate).setScale(8, RoundingMode.HALF_UP);
        BigDecimal pnl = exitPrice.subtract(open.entryPrice()).multiply(open.quantity()).setScale(8, RoundingMode.HALF_UP);
        BigDecimal totalCosts = open.entryFee().add(open.entrySlippage()).add(exitFee).add(exitSlip);
        BigDecimal netPnl = pnl.subtract(totalCosts).setScale(8, RoundingMode.HALF_UP);

        BigDecimal entryNotional = open.entryPrice().multiply(open.quantity());
        BigDecimal pnlPct = entryNotional.signum() == 0 ? BigDecimal.ZERO
                : pnl.divide(entryNotional, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        BigDecimal netPnlPct = entryNotional.signum() == 0 ? BigDecimal.ZERO
                : netPnl.divide(entryNotional, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

        return new SimulatedTrade(seq, OrderSide.BUY,
                open.entryPrice(), exitPrice, open.quantity(),
                open.openedAt(), closedAt,
                pnl, pnlPct.setScale(4, RoundingMode.HALF_UP),
                open.entryFee(), exitFee, open.entrySlippage(), exitSlip,
                netPnl, netPnlPct.setScale(4, RoundingMode.HALF_UP),
                reason, open.entryReason());
    }

    // ─── Override / config plumbing ────────────────────────────────────────────

    private TradingConfig applyOverrides(TradingConfig live, Map<String, Object> overrides) {
        TradingConfig copy = cloneConfig(live);
        if (overrides == null || overrides.isEmpty()) return copy;

        TradingConfig.Strategy s = copy.getStrategy();
        TradingConfig.Risk r     = copy.getRisk();
        TradingConfig.Costs c    = copy.getCosts();

        if (overrides.containsKey("emaShortPeriod"))  s.setEmaShortPeriod(asInt(overrides.get("emaShortPeriod")));
        if (overrides.containsKey("emaLongPeriod"))   s.setEmaLongPeriod(asInt(overrides.get("emaLongPeriod")));
        if (overrides.containsKey("rsiPeriod"))       s.setRsiPeriod(asInt(overrides.get("rsiPeriod")));
        if (overrides.containsKey("rsiOverbought"))   s.setRsiOverbought(asInt(overrides.get("rsiOverbought")));
        if (overrides.containsKey("rsiOversold"))     s.setRsiOversold(asInt(overrides.get("rsiOversold")));

        if (overrides.containsKey("takeProfitPct"))   r.setTakeProfitPct(asBd(overrides.get("takeProfitPct")));
        if (overrides.containsKey("stopLossPct"))     r.setStopLossPct(asBd(overrides.get("stopLossPct")));
        if (overrides.containsKey("maxPositionPct"))  r.setMaxPositionPct(asBd(overrides.get("maxPositionPct")));

        if (overrides.containsKey("feeRate"))         c.setFeeRate(asBd(overrides.get("feeRate")));
        if (overrides.containsKey("slippageRate"))    c.setSlippageRate(asBd(overrides.get("slippageRate")));

        return copy;
    }

    private TradingConfig cloneConfig(TradingConfig src) {
        TradingConfig copy = new TradingConfig();
        copy.setPairs(src.getPairs());
        copy.setIntervals(src.getIntervals());
        copy.setMode(src.getMode());
        copy.setPaperBalance(src.getPaperBalance());
        copy.setStrategyBalances(src.getStrategyBalances());
        copy.setPrimaryStrategy(src.getPrimaryStrategy());
        copy.setPollingIntervalSeconds(src.getPollingIntervalSeconds());

        TradingConfig.Strategy s = new TradingConfig.Strategy();
        s.setEmaShortPeriod(src.getStrategy().getEmaShortPeriod());
        s.setEmaLongPeriod(src.getStrategy().getEmaLongPeriod());
        s.setRsiPeriod(src.getStrategy().getRsiPeriod());
        s.setRsiOverbought(src.getStrategy().getRsiOverbought());
        s.setRsiOversold(src.getStrategy().getRsiOversold());
        copy.setStrategy(s);

        TradingConfig.Risk r = new TradingConfig.Risk();
        r.setMaxPositionPct(src.getRisk().getMaxPositionPct());
        r.setMaxConcurrentPositions(src.getRisk().getMaxConcurrentPositions());
        r.setMaxDailyLossPct(src.getRisk().getMaxDailyLossPct());
        r.setMaxConsecutiveLosses(src.getRisk().getMaxConsecutiveLosses());
        r.setTakeProfitPct(src.getRisk().getTakeProfitPct());
        r.setStopLossPct(src.getRisk().getStopLossPct());
        copy.setRisk(r);

        TradingConfig.Costs c = new TradingConfig.Costs();
        c.setFeeRate(src.getCosts().getFeeRate());
        c.setSlippageRate(src.getCosts().getSlippageRate());
        copy.setCosts(c);

        return copy;
    }

    private TradingStrategy resolveStrategy(StrategyType type, TradingConfig override) {
        if (type == StrategyType.EMA_CROSSOVER) {
            return new EmaCrossoverStrategy(override);
        }
        return strategies.stream()
                .filter(s -> s.strategyType() == type)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown strategy: " + type));
    }

    private BigDecimal resolveStartingBalance(BacktestRequest req, TradingConfig override) {
        if (req.startingBalance() != null) return req.startingBalance();
        Map<String, Map<StrategyType, BigDecimal>> all = override.getStrategyBalances();
        if (all != null) {
            Map<StrategyType, BigDecimal> forPair = all.get(req.pair());
            if (forPair != null && forPair.containsKey(req.strategy())) {
                return forPair.get(req.strategy());
            }
        }
        return override.getPaperBalance();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void validate(BacktestRequest req) {
        if (req.pair() == null || !tradingConfig.getPairs().contains(req.pair())) {
            throw new IllegalArgumentException("Unknown pair: " + req.pair()
                    + " (configured: " + tradingConfig.getPairs() + ")");
        }
        if (req.interval() == null || !tradingConfig.intervalLabels().contains(req.interval())) {
            throw new IllegalArgumentException("Unknown interval: " + req.interval()
                    + " (configured: " + tradingConfig.intervalLabels() + ")");
        }
        if (req.startDate() == null || req.endDate() == null
                || !req.endDate().isAfter(req.startDate())) {
            throw new IllegalArgumentException("end_date must be after start_date");
        }
    }

    private BarSeries buildBarSeries(String pair, String intervalLabel,
                                     Duration barDuration, List<Candlestick> candles) {
        BaseBarSeries series = new BaseBarSeries(pair + "_" + intervalLabel + "_bt");
        for (Candlestick c : candles) {
            ZonedDateTime end = c.getTimestamp().atZone(ZoneOffset.UTC).plus(barDuration);
            series.addBar(BaseBar.builder()
                    .timePeriod(barDuration)
                    .endTime(end)
                    .openPrice(DecimalNum.valueOf(c.getOpenPrice()))
                    .highPrice(DecimalNum.valueOf(c.getHighPrice()))
                    .lowPrice(DecimalNum.valueOf(c.getLowPrice()))
                    .closePrice(DecimalNum.valueOf(c.getClosePrice()))
                    .volume(DecimalNum.valueOf(c.getVolume()))
                    .build());
        }
        return series;
    }

    private static int intervalToMinutes(String label) {
        return switch (label) {
            case "15m"  -> 15;
            case "1h"   -> 60;
            case "4h"   -> 240;
            case "1d"   -> 1440;
            case "1w"   -> 10080;
            default     -> throw new IllegalArgumentException("Unknown interval label: " + label);
        };
    }

    private WalkForwardResult.VarianceMetrics computeVarianceMetrics(List<BacktestRunSummary> wins) {
        if (wins.size() < 2) {
            return new WalkForwardResult.VarianceMetrics(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "WILDLY_VARYING");
        }
        BigDecimal winRateMean = avg(wins, w -> w.stats().winRate());
        BigDecimal winRateSd   = sd(wins, w -> w.stats().winRate(), winRateMean);
        BigDecimal expSd       = sd(wins, w -> w.stats().expectancy(),
                                     avg(wins, w -> w.stats().expectancy()));
        BigDecimal pnlSd       = sd(wins, w -> w.stats().netPnl(),
                                     avg(wins, w -> w.stats().netPnl()));

        BigDecimal pnlMean = avg(wins, w -> w.stats().netPnl());
        String verdict;
        if (pnlMean.signum() <= 0) {
            verdict = "WILDLY_VARYING";       // negative across windows = no edge
        } else {
            BigDecimal cv = pnlSd.divide(pnlMean.abs(), 4, RoundingMode.HALF_UP);
            verdict = cv.compareTo(BigDecimal.valueOf(0.30)) < 0 ? "STABLE"
                    : cv.compareTo(BigDecimal.valueOf(0.70)) < 0 ? "REGIME_DEPENDENT"
                    : "WILDLY_VARYING";
        }
        return new WalkForwardResult.VarianceMetrics(
                winRateSd.setScale(2, RoundingMode.HALF_UP),
                expSd.setScale(2, RoundingMode.HALF_UP),
                pnlSd.setScale(2, RoundingMode.HALF_UP),
                verdict);
    }

    private static BigDecimal avg(List<BacktestRunSummary> ws,
                                   java.util.function.Function<BacktestRunSummary, BigDecimal> f) {
        BigDecimal sum = ws.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(ws.size()), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal sd(List<BacktestRunSummary> ws,
                                  java.util.function.Function<BacktestRunSummary, BigDecimal> f,
                                  BigDecimal mean) {
        BigDecimal acc = BigDecimal.ZERO;
        for (BacktestRunSummary w : ws) {
            BigDecimal d = f.apply(w).subtract(mean);
            acc = acc.add(d.multiply(d));
        }
        return BigDecimal.valueOf(Math.sqrt(
                acc.divide(BigDecimal.valueOf(ws.size() - 1), 8, RoundingMode.HALF_UP).doubleValue()));
    }

    private String autoLabel(BacktestRequest req) {
        return req.strategy() + " " + req.pair() + "/" + req.interval()
                + " " + req.startDate().toLocalDate() + "→" + req.endDate().toLocalDate();
    }

    private String safeLabel(BacktestRequest req) {
        return req.label() != null ? req.label() : autoLabel(req);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(8, RoundingMode.HALF_UP);
    }

    private static int asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(o.toString());
    }

    private static BigDecimal asBd(Object o) {
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(o.toString());
    }

    // ─── Public helpers used by controller ─────────────────────────────────────

    public List<BacktestRunSummary> listRecent(String pair, StrategyType strategy,
                                                String interval, int limit) {
        return backtestRunRepository
                .findRecentByFilter(pair, strategy, interval,
                        org.springframework.data.domain.PageRequest.of(0, Math.min(Math.max(limit, 1), 200)))
                .stream()
                .map(this::toSummary)
                .toList();
    }

    public BacktestRunDetail get(UUID id) {
        return backtestRunRepository.findById(id)
                .map(this::toDetail)
                .orElseThrow(() -> new IllegalArgumentException("Backtest run not found: " + id));
    }

    public void delete(UUID id) {
        backtestRunRepository.deleteById(id);
    }

    public BacktestRunSummary patchMetadata(UUID id, String label, String notes) {
        BacktestRun run = backtestRunRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Backtest run not found: " + id));
        if (label != null) run.setLabel(label);
        if (notes != null) run.setNotes(notes);
        return toSummary(backtestRunRepository.save(run));
    }

    private BacktestRunDetail toDetail(BacktestRun e) {
        return new BacktestRunDetail(e.getId(), e.getPair(), e.getStrategy(), e.getInterval(),
                e.getStartDate(), e.getEndDate(), e.getStartingBalance(),
                e.getParams() == null ? new HashMap<>() : e.getParams(),
                e.getStats(), e.getTrades(), e.getEquityCurve(),
                e.getLabel(), e.getNotes(), e.getCreatedAt());
    }

    private BacktestRunSummary toSummary(BacktestRun e) {
        return new BacktestRunSummary(e.getId(), e.getPair(), e.getStrategy(), e.getInterval(),
                e.getStartDate(), e.getEndDate(), e.getStartingBalance(),
                e.getStats(), e.getLabel(), e.getCreatedAt());
    }

    private BacktestRunSummary toSummary(BacktestRunDetail d) {
        return new BacktestRunSummary(d.id(), d.pair(), d.strategy(), d.interval(),
                d.startDate(), d.endDate(), d.startingBalance(),
                d.stats(), d.label(), d.createdAt());
    }
}
