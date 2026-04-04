package com.stefo.revolut_trading_bot.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SymbolResponse(
        @JsonProperty("symbol") String symbol,
        @JsonProperty("base_currency") String baseCurrency,
        @JsonProperty("quote_currency") String quoteCurrency,
        @JsonProperty("min_order_size") BigDecimal minOrderSize,
        @JsonProperty("max_order_size") BigDecimal maxOrderSize,
        @JsonProperty("min_price") BigDecimal minPrice,
        @JsonProperty("max_price") BigDecimal maxPrice
) {}
