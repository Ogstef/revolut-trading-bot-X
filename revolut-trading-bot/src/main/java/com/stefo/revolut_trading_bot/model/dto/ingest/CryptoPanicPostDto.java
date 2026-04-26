package com.stefo.revolut_trading_bot.model.dto.ingest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One CryptoPanic news item as pushed by the scraper microservice.
 *
 * {@link #currencyCodes} is uppercase tickers (e.g. "BTC", "ETH") — the ingest
 * service maps these to the bot's canonical pair strings ("BTC-EUR", etc.)
 * before writing rows to {@code sentiment_snapshots}.
 */
public record CryptoPanicPostDto(
        @NotBlank String externalId,
        @NotBlank String title,
        @NotBlank String url,
        @NotNull @Valid CryptoPanicVotesDto votes,
        @NotNull List<String> currencyCodes,    // e.g. ["BTC", "ETH"]
        @NotNull OffsetDateTime publishedAt
) {}
