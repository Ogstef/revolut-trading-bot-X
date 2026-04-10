package com.stefo.revolut_trading_bot.strategy;

import org.ta4j.core.BarSeries;

/**
 * Contract for all trading strategies.
 *
 * A strategy reads a fully-populated BarSeries and returns a Signal.
 * It must be stateless — same series in, same signal out.
 */
public interface TradingStrategy {
    Signal evaluate(BarSeries series);
}
