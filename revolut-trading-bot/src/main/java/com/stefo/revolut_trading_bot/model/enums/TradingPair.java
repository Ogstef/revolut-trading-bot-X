package com.stefo.revolut_trading_bot.model.enums;

public enum TradingPair {
    BTC_EUR("BTC-EUR");

    private final String symbol;

    TradingPair(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }
}
