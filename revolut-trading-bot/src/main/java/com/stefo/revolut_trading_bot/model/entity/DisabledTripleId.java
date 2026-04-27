package com.stefo.revolut_trading_bot.model.entity;

import com.stefo.revolut_trading_bot.model.enums.StrategyType;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key for {@link DisabledTriple}. Field names + types must match
 * the @Id-annotated fields on the entity.
 */
public class DisabledTripleId implements Serializable {

    private String pair;
    private StrategyType strategyName;
    private String interval;

    public DisabledTripleId() {}

    public DisabledTripleId(String pair, StrategyType strategyName, String interval) {
        this.pair = pair;
        this.strategyName = strategyName;
        this.interval = interval;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DisabledTripleId that)) return false;
        return Objects.equals(pair, that.pair)
                && strategyName == that.strategyName
                && Objects.equals(interval, that.interval);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pair, strategyName, interval);
    }
}
