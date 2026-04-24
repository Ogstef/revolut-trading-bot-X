package com.stefo.revolut_trading_bot.portfolio;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.model.enums.TradingVehicle;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory virtual-balance ledger for leveraged portfolios.
 *
 * Keyed by (pair, strategy, vehicle) — balances are fully isolated from spot.
 * Seeded on startup from trading.leverage.collateral-per-strategy for each
 * active (pair × strategy × ratio) triple.
 *
 * Debit on position open, credit (collateral + netPnl) on position close.
 *
 * State is not persisted — survives only as long as the JVM does. Symmetric
 * with the existing TradingConfig.strategyBalances pattern for spot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleBalanceService {

    private final TradingConfig config;

    private final ConcurrentHashMap<String, BigDecimal> balances = new ConcurrentHashMap<>();

    @PostConstruct
    public void seed() {
        TradingConfig.Leverage lev = config.getLeverage();
        if (!lev.isEnabled()) {
            log.info("Leverage disabled — VehicleBalanceService seeded empty");
            return;
        }
        BigDecimal starting = lev.getCollateralPerStrategy();
        int seeded = 0;
        for (String pair : lev.getPairs()) {
            for (StrategyType strategy : StrategyType.values()) {
                for (Integer ratio : lev.getRatios()) {
                    TradingVehicle vehicle = TradingVehicle.forLeverage(ratio);
                    balances.put(key(pair, strategy, vehicle), starting);
                    seeded++;
                }
            }
        }
        log.info("VehicleBalanceService seeded {} leveraged portfolios with {} EUR each", seeded, starting);
    }

    /** Balance currently available for this (pair, strategy, vehicle). Zero if not seeded. */
    public BigDecimal available(String pair, StrategyType strategy, TradingVehicle vehicle) {
        return balances.getOrDefault(key(pair, strategy, vehicle), BigDecimal.ZERO);
    }

    /** Deducts amount from the ledger. No-op if amount ≤ 0. Caller must have already validated availability. */
    public synchronized void debit(String pair, StrategyType strategy, TradingVehicle vehicle, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) return;
        String k = key(pair, strategy, vehicle);
        BigDecimal current = balances.getOrDefault(k, BigDecimal.ZERO);
        balances.put(k, current.subtract(amount));
        log.debug("Debit {} EUR from {} — new balance {}", amount, k, balances.get(k));
    }

    /** Adds amount to the ledger (e.g. collateral + netPnl returned on close). No-op if amount ≤ 0. */
    public synchronized void credit(String pair, StrategyType strategy, TradingVehicle vehicle, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) return;
        String k = key(pair, strategy, vehicle);
        BigDecimal current = balances.getOrDefault(k, BigDecimal.ZERO);
        balances.put(k, current.add(amount));
        log.debug("Credit {} EUR to {} — new balance {}", amount, k, balances.get(k));
    }

    /** Snapshot of all known balances — exposed to the UI via stats endpoints later. */
    public Map<String, BigDecimal> snapshot() {
        return Map.copyOf(balances);
    }

    private static String key(String pair, StrategyType strategy, TradingVehicle vehicle) {
        return pair + "|" + strategy.name() + "|" + vehicle.name();
    }
}
