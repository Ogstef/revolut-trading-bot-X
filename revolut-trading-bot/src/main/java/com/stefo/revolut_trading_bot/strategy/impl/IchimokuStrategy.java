package com.stefo.revolut_trading_bot.strategy.impl;

import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.strategy.Signal;
import com.stefo.revolut_trading_bot.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ichimoku.IchimokuKijunSenIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuSenkouSpanAIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuSenkouSpanBIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuTenkanSenIndicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Ichimoku Cloud strategy — the most conditions-rich signal of all strategies.
 *
 * Only fires when multiple Ichimoku components agree: TK cross + price above/below
 * cloud + cloud colour confirmation. The most selective strategy — fewest signals
 * but highest quality. Needs 79+ bars to warm up (52 for SpanB + 26 offset + 1).
 *
 * Signals:
 *   BUY  → TK cross bullish AND price above cloud AND cloud is green (SpanA > SpanB)
 *   SELL → TK cross bearish AND price below cloud AND cloud is red (SpanB > SpanA)
 *   HOLD → any condition unmet (price inside cloud = "in the fog")
 *
 * Signal field mapping:
 *   emaShort → Tenkan-sen value
 *   emaLong  → Kijun-sen value
 *   rsi      → SpanA value (cloud top — most useful display value)
 */
@Slf4j
@Component
public class IchimokuStrategy implements TradingStrategy {

    // Standard Ichimoku periods
    private static final int TENKAN_PERIOD = 9;
    private static final int KIJUN_PERIOD  = 26;
    private static final int SPAN_B_PERIOD = 52;

    // Needs SpanB lookback (52) + cloud offset (26) + prev bar comparison (1)
    private static final int MIN_BARS = SPAN_B_PERIOD + KIJUN_PERIOD + 1;

    @Override
    public StrategyType strategyType() {
        return StrategyType.ICHIMOKU;
    }

    @Override
    public Signal evaluate(BarSeries series, String pair) {
        Instant now  = Instant.now();
        int lastIdx  = series.getEndIndex();

        if (lastIdx < MIN_BARS) {
            String reason = String.format("ICHIMOKU: insufficient data — %d bars, need %d",
                    lastIdx + 1, MIN_BARS + 1);
            log.warn(reason);
            return hold(reason, pair, now, null, null, null, null);
        }

        IchimokuTenkanSenIndicator tenkan = new IchimokuTenkanSenIndicator(series, TENKAN_PERIOD);
        IchimokuKijunSenIndicator  kijun  = new IchimokuKijunSenIndicator(series, KIJUN_PERIOD);
        IchimokuSenkouSpanAIndicator spanA = new IchimokuSenkouSpanAIndicator(series, tenkan, kijun, KIJUN_PERIOD);
        IchimokuSenkouSpanBIndicator spanB = new IchimokuSenkouSpanBIndicator(series, SPAN_B_PERIOD);

        int prevIdx = lastIdx - 1;

        double tenkanNow  = tenkan.getValue(lastIdx).doubleValue();
        double tenkanPrev = tenkan.getValue(prevIdx).doubleValue();
        double kijunNow   = kijun.getValue(lastIdx).doubleValue();
        double kijunPrev  = kijun.getValue(prevIdx).doubleValue();
        double spanANow   = spanA.getValue(lastIdx).doubleValue();
        double spanBNow   = spanB.getValue(lastIdx).doubleValue();
        double closeNow   = series.getBar(lastIdx).getClosePrice().doubleValue();

        BigDecimal tenkanVal = bd(tenkanNow);
        BigDecimal kijunVal  = bd(kijunNow);
        BigDecimal spanAVal  = bd(spanANow);
        BigDecimal priceVal  = bd(closeNow);

        double cloudTop    = Math.max(spanANow, spanBNow);
        double cloudBottom = Math.min(spanANow, spanBNow);
        boolean bullishCloud = spanANow > spanBNow;
        boolean bearishCloud = spanBNow > spanANow;

        log.info("[ICHIMOKU] pair={} tenkan={} kijun={} spanA={} spanB={} close={} cloudTop={} cloudBottom={}",
                pair, tenkanVal, kijunVal, spanAVal, bd(spanBNow), priceVal, bd(cloudTop), bd(cloudBottom));

        // BUY: TK bullish cross + price above cloud + green cloud
        boolean tkBullishCross = tenkanPrev <= kijunPrev && tenkanNow > kijunNow;
        boolean aboveCloud     = closeNow > cloudTop;
        if (tkBullishCross && aboveCloud && bullishCloud) {
            String reason = String.format(
                    "Ichimoku BUY — TK cross bullish, close=%.2f above cloud [%.2f,%.2f], green cloud",
                    closeNow, cloudBottom, cloudTop);
            return new Signal(SignalType.BUY, BigDecimal.valueOf(82), reason,
                    pair, null, StrategyType.ICHIMOKU, now, tenkanVal, kijunVal, spanAVal, priceVal);
        }

        // SELL: TK bearish cross + price below cloud + red cloud
        boolean tkBearishCross = tenkanPrev >= kijunPrev && tenkanNow < kijunNow;
        boolean belowCloud     = closeNow < cloudBottom;
        if (tkBearishCross && belowCloud && bearishCloud) {
            String reason = String.format(
                    "Ichimoku SELL — TK cross bearish, close=%.2f below cloud [%.2f,%.2f], red cloud",
                    closeNow, cloudBottom, cloudTop);
            return new Signal(SignalType.SELL, BigDecimal.valueOf(78), reason,
                    pair, null, StrategyType.ICHIMOKU, now, tenkanVal, kijunVal, spanAVal, priceVal);
        }

        String fog = closeNow >= cloudBottom && closeNow <= cloudTop ? " (price inside cloud)" : "";
        String reason = String.format(
                "Ichimoku HOLD — no confirmed signal%s tenkan=%.2f kijun=%.2f cloud=[%.2f,%.2f]",
                fog, tenkanNow, kijunNow, cloudBottom, cloudTop);
        return hold(reason, pair, now, tenkanVal, kijunVal, spanAVal, priceVal);
    }

    private Signal hold(String reason, String pair, Instant evaluatedAt,
                        BigDecimal tenkan, BigDecimal kijun, BigDecimal spanA, BigDecimal price) {
        return new Signal(SignalType.HOLD, BigDecimal.valueOf(50), reason,
                pair, null, StrategyType.ICHIMOKU, evaluatedAt, tenkan, kijun, spanA, price);
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }
}
