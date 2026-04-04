package com.stefo.revolut_trading_bot.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketTradeResponse(
        @JsonProperty("id") String id,
        @JsonProperty("price") BigDecimal price,
        @JsonProperty("size") BigDecimal size,
        @JsonProperty("side") String side,
        @JsonProperty("timestamp") long timestamp
) {}
