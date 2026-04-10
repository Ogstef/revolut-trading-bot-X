package com.stefo.revolut_trading_bot.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CandleResponse(
        @JsonProperty("start") long start,   // candle open timestamp — Unix epoch ms
        @JsonProperty("open") BigDecimal open,
        @JsonProperty("high") BigDecimal high,
        @JsonProperty("low") BigDecimal low,
        @JsonProperty("close") BigDecimal close,
        @JsonProperty("volume") BigDecimal volume
) {}
