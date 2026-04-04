package com.stefo.revolut_trading_bot.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BalanceResponse(
        @JsonProperty("currency") String currency,
        @JsonProperty("available") BigDecimal available,
        @JsonProperty("locked") BigDecimal locked
) {}
