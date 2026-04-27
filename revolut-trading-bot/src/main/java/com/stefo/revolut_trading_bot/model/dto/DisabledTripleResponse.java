package com.stefo.revolut_trading_bot.model.dto;

import com.stefo.revolut_trading_bot.model.entity.DisabledTriple;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;

import java.time.LocalDateTime;

public record DisabledTripleResponse(
        String pair,
        StrategyType strategy,
        String interval,
        LocalDateTime disabledAt,
        String reason
) {
    public static DisabledTripleResponse from(DisabledTriple e) {
        return new DisabledTripleResponse(
                e.getPair(),
                e.getStrategyName(),
                e.getInterval(),
                e.getDisabledAt(),
                e.getReason()
        );
    }
}
