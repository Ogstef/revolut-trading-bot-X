package com.stefo.revolut_trading_bot.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderRequest(
        @JsonProperty("client_order_id") String clientOrderId,
        @JsonProperty("symbol") String symbol,
        @JsonProperty("side") String side,
        @JsonProperty("order_configuration") OrderConfiguration orderConfiguration
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderConfiguration(
            @JsonProperty("market") MarketConfig market,
            @JsonProperty("limit") LimitConfig limit
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketConfig(
            @JsonProperty("quote_size") String quoteSize
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LimitConfig(
            @JsonProperty("base_size") String baseSize,
            @JsonProperty("price") String price
    ) {}

    public static OrderRequest marketOrder(String clientOrderId, String symbol, String side, BigDecimal quoteSize) {
        return new OrderRequest(clientOrderId, symbol, side,
                new OrderConfiguration(new MarketConfig(quoteSize.toPlainString()), null));
    }

    public static OrderRequest limitOrder(String clientOrderId, String symbol, String side,
                                           BigDecimal baseSize, BigDecimal price) {
        return new OrderRequest(clientOrderId, symbol, side,
                new OrderConfiguration(null, new LimitConfig(baseSize.toPlainString(), price.toPlainString())));
    }
}
