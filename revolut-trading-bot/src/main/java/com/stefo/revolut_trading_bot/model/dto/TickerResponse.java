package com.stefo.revolut_trading_bot.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

// Response item from GET /tickers — real-time best bid/ask and last traded price.
// Useful for getting current market price without consuming a candle API call.
@JsonIgnoreProperties(ignoreUnknown = true)
public record TickerResponse(
        @JsonProperty("symbol") String symbol,
        @JsonProperty("bid") BigDecimal bid,
        @JsonProperty("ask") BigDecimal ask,
        @JsonProperty("mid") BigDecimal mid,
        @JsonProperty("last_price") BigDecimal lastPrice
) {}
