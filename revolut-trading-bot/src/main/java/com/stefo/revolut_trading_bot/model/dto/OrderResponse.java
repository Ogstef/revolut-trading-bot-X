package com.stefo.revolut_trading_bot.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

// Response shape for GET /orders/{venue_order_id}
// POST /orders returns a slimmer PlaceOrderResponse — see that record for placement responses
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderResponse(
        @JsonProperty("id") String id,
        @JsonProperty("client_order_id") String clientOrderId,
        @JsonProperty("symbol") String symbol,
        @JsonProperty("side") String side,                          // "buy" or "sell" (lowercase)
        @JsonProperty("type") String type,                          // "market" or "limit"
        @JsonProperty("status") String status,                      // "new", "filled", "cancelled", etc.
        @JsonProperty("quantity") String quantity,
        @JsonProperty("filled_quantity") String filledQuantity,
        @JsonProperty("price") String price,
        @JsonProperty("average_fill_price") String averageFillPrice,
        @JsonProperty("created_date") Long createdDate,             // Unix epoch ms
        @JsonProperty("updated_date") Long updatedDate
) {}
