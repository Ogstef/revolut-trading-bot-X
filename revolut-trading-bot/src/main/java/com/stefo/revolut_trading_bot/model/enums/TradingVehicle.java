package com.stefo.revolut_trading_bot.model.enums;

import lombok.Getter;

@Getter
public enum TradingVehicle {
    SPOT(1),
    LEV_3X(3),
    LEV_5X(5),
    LEV_10X(10);

    private final int leverage;

    TradingVehicle(int leverage) {
        this.leverage = leverage;
    }

    public boolean isLeveraged() {
        return leverage > 1;
    }

    public static TradingVehicle forLeverage(int leverage) {
        for (TradingVehicle v : values()) {
            if (v.leverage == leverage) return v;
        }
        throw new IllegalArgumentException("No TradingVehicle for leverage=" + leverage);
    }
}
