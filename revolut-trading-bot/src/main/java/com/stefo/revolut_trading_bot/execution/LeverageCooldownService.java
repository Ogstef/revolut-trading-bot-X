package com.stefo.revolut_trading_bot.execution;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.model.enums.TradingVehicle;
import com.stefo.revolut_trading_bot.repository.TradeRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory post-close cooldown for leveraged trades.
 *
 * After a close on (pair, interval, strategy, vehicle), blocks new opens on that
 * same quadruple for {@code trading.leverage.cooldown-minutes} minutes. Breaks
 * the every-30-second re-entry churn without affecting SPOT or the normal
 * monitor/close path.
 *
 * Keyed by (pair, interval, strategy, vehicle) — matches the quadruple that
 * {@link OrderExecutionService} routes through.
 *
 * State lives only in the JVM. On startup, {@link #warmup()} preloads the map
 * from the most recent leveraged close per quadruple so a restart does not
 * reset all cooldowns to zero.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeverageCooldownService {

    private final TradingConfig config;
    private final TradeRepository tradeRepository;

    private final ConcurrentHashMap<String, LocalDateTime> lastClosedAt = new ConcurrentHashMap<>();

    @PostConstruct
    void warmup() {
        if (!config.getLeverage().isEnabled() || config.getLeverage().getCooldownMinutes() <= 0) {
            log.info("LeverageCooldownService warmup skipped — leverage disabled or cooldown=0");
            return;
        }
        int loaded = 0;
        for (Object[] row : tradeRepository.maxClosedAtPerLeveragedQuadruple()) {
            String pair                 = (String) row[0];
            String interval             = (String) row[1];
            StrategyType strategy       = (StrategyType) row[2];
            TradingVehicle vehicle      = (TradingVehicle) row[3];
            LocalDateTime maxClosedAt   = (LocalDateTime) row[4];
            if (maxClosedAt == null) continue;
            lastClosedAt.put(key(pair, interval, strategy, vehicle), maxClosedAt);
            loaded++;
        }
        log.info("LeverageCooldownService warmup — loaded {} quadruple(s) from DB; cooldown={}min",
                loaded, config.getLeverage().getCooldownMinutes());
    }

    /** Records the close of a leveraged position. No-op for SPOT. */
    public void recordClose(Position p) {
        if (p == null || p.getVehicle() == null || p.getVehicle() == TradingVehicle.SPOT) return;
        lastClosedAt.put(
                key(p.getPair(), p.getInterval(), p.getStrategyName(), p.getVehicle()),
                LocalDateTime.now());
    }

    /**
     * @return true when a new open is allowed, false when cooldown is still active.
     *         Always true when {@code cooldownMinutes <= 0}.
     */
    public boolean canOpen(String pair, String interval, StrategyType strategy,
                           TradingVehicle vehicle, LocalDateTime now) {
        int cooldown = config.getLeverage().getCooldownMinutes();
        if (cooldown <= 0) return true;
        LocalDateTime last = lastClosedAt.get(key(pair, interval, strategy, vehicle));
        if (last == null) return true;
        return Duration.between(last, now).toMinutes() >= cooldown;
    }

    private static String key(String pair, String interval, StrategyType strategy, TradingVehicle vehicle) {
        return pair + "|" + interval + "|" + strategy.name() + "|" + vehicle.name();
    }
}
