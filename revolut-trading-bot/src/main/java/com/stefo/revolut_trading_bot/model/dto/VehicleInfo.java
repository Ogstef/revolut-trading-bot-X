package com.stefo.revolut_trading_bot.model.dto;

import com.stefo.revolut_trading_bot.model.enums.TradingVehicle;

/**
 * Info on an available trading vehicle — returned by GET /api/vehicles.
 *
 * SPOT is always active. LEV_*X entries are active only when
 * trading.leverage.enabled=true AND the ratio is present in trading.leverage.ratios.
 */
public record VehicleInfo(TradingVehicle name, int leverage, boolean active) {}
