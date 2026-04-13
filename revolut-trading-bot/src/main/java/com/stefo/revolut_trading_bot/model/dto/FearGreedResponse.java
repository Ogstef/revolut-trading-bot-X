package com.stefo.revolut_trading_bot.model.dto;

/**
 * Fear & Greed Index snapshot returned by GET /api/market/fear-greed.
 *
 * @param value          0–100 (0 = Extreme Fear, 100 = Extreme Greed)
 * @param classification e.g. "Extreme Fear", "Fear", "Neutral", "Greed", "Extreme Greed"
 * @param timestamp      Unix epoch seconds of the reading
 */
public record FearGreedResponse(int value, String classification, long timestamp) {}
