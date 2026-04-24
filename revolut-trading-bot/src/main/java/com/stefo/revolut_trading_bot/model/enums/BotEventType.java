package com.stefo.revolut_trading_bot.model.enums;

public enum BotEventType {
    POSITION_OPENED,
    POSITION_CLOSED,
    POSITION_LIQUIDATED,
    CIRCUIT_BREAKER_TRIPPED,
    CIRCUIT_BREAKER_RESET,
    BOT_STOPPED,
    BOT_RESUMED,
    CONFIG_CHANGED
}
