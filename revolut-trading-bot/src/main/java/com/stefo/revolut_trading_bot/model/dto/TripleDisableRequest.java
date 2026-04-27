package com.stefo.revolut_trading_bot.model.dto;

/**
 * Optional body for POST /api/triples/{pair}/{strategy}/{interval}/disable.
 * `reason` is free-form audit text — surfaced in /api/triples/disabled and
 * the bot_events activity feed.
 */
public record TripleDisableRequest(String reason) {}
