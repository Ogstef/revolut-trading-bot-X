package com.stefo.revolut_trading_bot.strategy;

import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import org.ta4j.core.BarSeries;

/**
 * Contract for all trading strategies.
 *
 * A strategy reads a fully-populated BarSeries and returns a Signal.
 * It must be stateless — same series in, same signal out.
 *
 * name() is used as the stable identifier throughout the system:
 * it tags positions, trades, and signal logs so results can be
 * compared across strategies without ambiguity.
 */
public interface TradingStrategy {

    /**
     * Evaluates the strategy on the given bar series for a specific trading pair.
     *
     * @param series the fully-populated BarSeries for this pair
     * @param pair   the pair being evaluated (e.g. "BTC-EUR") — written into the returned Signal
     */
    Signal evaluate(BarSeries series, String pair);

    /**
     * Returns the enum constant that uniquely identifies this strategy.
     * Used to tag signals, positions, and trades so results can be compared
     * across strategies without string literals anywhere in the codebase.
     */
    StrategyType strategyType();
}
