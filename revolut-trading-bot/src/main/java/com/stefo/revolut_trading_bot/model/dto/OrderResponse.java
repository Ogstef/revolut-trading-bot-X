package com.stefo.revolut_trading_bot.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderResponse(
        @JsonProperty("id") String id,
        @JsonProperty("client_order_id") String clientOrderId,
        @JsonProperty("symbol") String symbol,
        @JsonProperty("side") String side,
        @JsonProperty("status") String status,
        @JsonProperty("filled_size") BigDecimal filledSize,
        @JsonProperty("filled_value") BigDecimal filledValue,
        @JsonProperty("average_price") BigDecimal averagePrice,
        @JsonProperty("created_at") String createdAt
) {}
