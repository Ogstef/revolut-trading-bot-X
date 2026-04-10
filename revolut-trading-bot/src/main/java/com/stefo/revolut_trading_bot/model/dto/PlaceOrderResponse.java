package com.stefo.revolut_trading_bot.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// Slim response returned by POST /orders.
// For full order details, fetch GET /orders/{venue_order_id} → OrderResponse.
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlaceOrderResponse(
        @JsonProperty("venue_order_id") String venueOrderId,
        @JsonProperty("client_order_id") String clientOrderId,
        @JsonProperty("state") String state    // "pending_new", "new", "filled", "cancelled", "rejected"
) {}
