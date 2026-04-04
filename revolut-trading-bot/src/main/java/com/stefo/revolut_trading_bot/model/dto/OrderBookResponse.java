package com.stefo.revolut_trading_bot.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderBookResponse(
        @JsonProperty("bids") List<PriceLevel> bids,
        @JsonProperty("asks") List<PriceLevel> asks
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PriceLevel(
            @JsonProperty("price") BigDecimal price,
            @JsonProperty("size") BigDecimal size
    ) {}
}
