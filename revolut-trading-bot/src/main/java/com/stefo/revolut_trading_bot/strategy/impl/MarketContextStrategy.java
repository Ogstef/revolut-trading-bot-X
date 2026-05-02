package com.stefo.revolut_trading_bot.strategy.impl;

import com.stefo.revolut_trading_bot.config.MarketContextConfig;
import com.stefo.revolut_trading_bot.market.MarketContext;
import com.stefo.revolut_trading_bot.market.MarketContextService;
import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.strategy.Signal;
import com.stefo.revolut_trading_bot.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Standalone macro-context strategy: trades on Fear & Greed + order-book imbalance only.
 * Reuses no candle indicators — the candle series is consulted only for the last close
 * price stored on the {@link Signal} payload.
 *
 * Thesis: contrarian on macro sentiment (Buffett "be greedy when fearful"), confirmed by
 * order-book pressure in the same direction. Entries are deliberately rare.
 *
 * F&G is interval-agnostic and the order book is read live per-pair, so within a single
 * cycle this strategy emits the same signal across all 5 configured intervals for a given
 * pair. That is intentional — different intervals have different TP/SL, so we can compare
 * holding-period outcomes from identical entries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketContextStrategy implements TradingStrategy {

    private final MarketContextConfig config;
    private final MarketContextService marketContextService;

    @Override
    public StrategyType strategyType() {
        return StrategyType.MARKET_CONTEXT;
    }

    @Override
    public Signal evaluate(BarSeries series, String pair) {
        Instant now = Instant.now();
        BigDecimal price = SentimentStrategyHelpers.lastClose(series);
        MarketContext ctx = marketContextService.getContext(pair);

        if (ctx.fearGreedValue() == null || ctx.bidAskRatio() == null) {
            return hold("market context unavailable", pair, now, price, null, null);
        }

        int fg = ctx.fearGreedValue();
        BigDecimal ob = ctx.bidAskRatio();
        BigDecimal fgBd = BigDecimal.valueOf(fg);

        boolean buyFgOk = fg <= config.getBuyFearGreedMax();
        boolean buyObOk = ob.compareTo(config.getBuyBidAskRatioMin()) >= 0;
        boolean sellFgOk = fg >= config.getSellFearGreedMin();
        boolean sellObOk = ob.compareTo(config.getSellBidAskRatioMax()) <= 0;

        if (buyFgOk && buyObOk) {
            BigDecimal confidence = buyConfidence(fg, ob);
            String reason = "F&G=%d (%s), bid/ask=%s — extreme fear + bid pressure"
                    .formatted(fg, ctx.fearGreedClassification(), ob.toPlainString());
            log.info("[{}] MARKET_CONTEXT BUY {}", pair, reason);
            return new Signal(SignalType.BUY, confidence, reason,
                    pair, null, StrategyType.MARKET_CONTEXT, now,
                    fgBd, ob, null, price);
        }
        if (sellFgOk && sellObOk) {
            BigDecimal confidence = sellConfidence(fg, ob);
            String reason = "F&G=%d (%s), bid/ask=%s — extreme greed + ask pressure"
                    .formatted(fg, ctx.fearGreedClassification(), ob.toPlainString());
            log.info("[{}] MARKET_CONTEXT SELL {}", pair, reason);
            return new Signal(SignalType.SELL, confidence, reason,
                    pair, null, StrategyType.MARKET_CONTEXT, now,
                    fgBd, ob, null, price);
        }

        String reason = "F&G=%d (%s), bid/ask=%s — no contrarian signal"
                .formatted(fg, ctx.fearGreedClassification(), ob.toPlainString());
        return hold(reason, pair, now, price, fgBd, ob);
    }

    /**
     * BUY confidence: linear blend of how deep the fear reading is and how heavy the bid
     * pressure is. Maps to [50, 100]. Each input contributes equally — extreme readings on
     * both sides yield the highest confidence.
     */
    private BigDecimal buyConfidence(int fg, BigDecimal ob) {
        BigDecimal fgScore = boundedScore(
                BigDecimal.valueOf(config.getBuyFearGreedMax() - fg),
                BigDecimal.valueOf(config.getBuyFearGreedMax()));
        BigDecimal obSpan = ob.subtract(config.getBuyBidAskRatioMin());
        BigDecimal obScore = boundedScore(obSpan, config.getBuyBidAskRatioMin());
        return blend(fgScore, obScore);
    }

    private BigDecimal sellConfidence(int fg, BigDecimal ob) {
        BigDecimal fgScore = boundedScore(
                BigDecimal.valueOf(fg - config.getSellFearGreedMin()),
                BigDecimal.valueOf(100 - config.getSellFearGreedMin()));
        BigDecimal obSpan = config.getSellBidAskRatioMax().subtract(ob);
        BigDecimal obScore = boundedScore(obSpan, config.getSellBidAskRatioMax());
        return blend(fgScore, obScore);
    }

    private static BigDecimal boundedScore(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) return BigDecimal.ZERO;
        BigDecimal raw = numerator.divide(denominator, 4, RoundingMode.HALF_UP);
        if (raw.signum() < 0) return BigDecimal.ZERO;
        if (raw.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE;
        return raw;
    }

    private static BigDecimal blend(BigDecimal fgScore, BigDecimal obScore) {
        BigDecimal avg = fgScore.add(obScore).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(50)
                .add(avg.multiply(BigDecimal.valueOf(50)))
                .setScale(0, RoundingMode.HALF_UP);
    }

    private static Signal hold(String reason, String pair, Instant now, BigDecimal price,
                               BigDecimal fgSlot, BigDecimal obSlot) {
        return new Signal(SignalType.HOLD, BigDecimal.valueOf(50), reason,
                pair, null, StrategyType.MARKET_CONTEXT, now,
                fgSlot, obSlot, null, price);
    }
}
