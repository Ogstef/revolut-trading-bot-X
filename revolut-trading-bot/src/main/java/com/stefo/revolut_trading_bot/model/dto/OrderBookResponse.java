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
    // Revolut X uses abbreviated field names in PriceLevel objects:
    // p = price, q = quantity (aggregated at this level), pdt = publication timestamp ms
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PriceLevel(
            @JsonProperty("p") BigDecimal price,
            @JsonProperty("q") BigDecimal quantity
    ) {}
}
