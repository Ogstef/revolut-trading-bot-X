package com.stefo.revolut_trading_bot.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * Threshold configuration for the standalone {@code MARKET_CONTEXT} strategy.
 *
 * The strategy is contrarian on macro sentiment, confirmed by microstructure
 * pressure in the same direction:
 *   BUY  when F&G ≤ buyFearGreedMax    AND  bid/ask volume ratio ≥ buyBidAskRatioMin
 *   SELL when F&G ≥ sellFearGreedMin   AND  bid/ask volume ratio ≤ sellBidAskRatioMax
 *   HOLD otherwise
 */
@Data
@Validated
@ConfigurationProperties(prefix = "market-context")
public class MarketContextConfig {

    @Min(0) @Max(100)
    private int buyFearGreedMax = 25;

    @NotNull @Positive
    private BigDecimal buyBidAskRatioMin = new BigDecimal("1.20");

    @Min(0) @Max(100)
    private int sellFearGreedMin = 75;

    @NotNull @Positive
    private BigDecimal sellBidAskRatioMax = new BigDecimal("0.83");
}
