package com.stefo.revolut_trading_bot.strategy.impl;

import com.stefo.revolut_trading_bot.model.enums.SignalType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.strategy.Signal;
import com.stefo.revolut_trading_bot.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.volume.MoneyFlowIndexIndicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Money Flow Index (MFI) strategy.
 *
 * RSI weighted by volume — detects when buying/selling pressure (via volume)
 * diverges from price. The only strategy that uses volume data.
 * Range: 0–100. Overbought > 80, oversold < 20.
 *
 * Signals:
 *   BUY  → MFI crosses above 20 (recovering from oversold with volume confirmation)
 *   SELL → MFI crosses above 80 (entering overbought with volume confirmation)
 *   HOLD → else
 *
 * Signal field mapping:
 *   emaShort → null
 *   emaLong  → null
 *   rsi      → current MFI value (0–100)
 */
@Slf4j
@Component
public class MfiStrategy implements TradingStrategy {

    private static final int    PERIOD     = 14;
    private static final double OVERSOLD   = 20.0;
    private static final double OVERBOUGHT = 80.0;

    // Need at least PERIOD + 1 bars for a prev/current comparison
    private static final int MIN_BARS = PERIOD + 1;

    @Override
    public StrategyType strategyType() {
        return StrategyType.MFI;
    }

    @Override
    public Signal evaluate(BarSeries series, String pair) {
        Instant now    = Instant.now();
        int lastIdx    = series.getEndIndex();

        if (lastIdx < MIN_BARS) {
            String reason = String.format("MFI: insufficient data — %d bars, need %d",
                    lastIdx + 1, MIN_BARS + 1);
            log.warn(reason);
            return hold(reason, pair, now, null);
        }

        MoneyFlowIndexIndicator mfi = new MoneyFlowIndexIndicator(series, PERIOD);

        int prevIdx = lastIdx - 1;
        double mfiNow  = mfi.getValue(lastIdx).doubleValue();
        double mfiPrev = mfi.getValue(prevIdx).doubleValue();

        BigDecimal mfiVal = bd(mfiNow);

        log.info("[MFI] pair={} mfi={} prev={}", pair, mfiVal, bd(mfiPrev));

        if (mfiPrev < OVERSOLD && mfiNow >= OVERSOLD) {
            String reason = String.format("MFI recovered from oversold — mfi=%.1f (was %.1f, crossed %.0f)",
                    mfiNow, mfiPrev, OVERSOLD);
            return new Signal(SignalType.BUY, BigDecimal.valueOf(75), reason,
                    pair, StrategyType.MFI, now, null, null, mfiVal,
                    bd(series.getBar(lastIdx).getClosePrice().doubleValue()));
        }

        if (mfiPrev < OVERBOUGHT && mfiNow >= OVERBOUGHT) {
            String reason = String.format("MFI entered overbought — mfi=%.1f (was %.1f, crossed %.0f)",
                    mfiNow, mfiPrev, OVERBOUGHT);
            return new Signal(SignalType.SELL, BigDecimal.valueOf(71), reason,
                    pair, StrategyType.MFI, now, null, null, mfiVal,
                    bd(series.getBar(lastIdx).getClosePrice().doubleValue()));
        }

        String zone   = mfiNow < OVERSOLD ? "oversold" : mfiNow > OVERBOUGHT ? "overbought" : "neutral";
        String reason = String.format("MFI no threshold crossing — mfi=%.1f (%s)", mfiNow, zone);
        return hold(reason, pair, now, mfiVal);
    }

    private Signal hold(String reason, String pair, Instant evaluatedAt, BigDecimal mfiVal) {
        return new Signal(SignalType.HOLD, BigDecimal.valueOf(50), reason,
                pair, StrategyType.MFI, evaluatedAt, null, null, mfiVal, null);
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }
}
