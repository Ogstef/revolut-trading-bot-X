package com.stefo.revolut_trading_bot.model.dto.ingest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One Reddit post as pushed by the scraper microservice.
 *
 * Mirrors {@code RedditPost} in {@code scrapers/sentiment-scraper/src/.../models.py}
 * — field names on the wire are camelCase to match Jackson's default.
 */
public record RedditPostDto(
        @NotBlank String externalId,        // e.g. "t3_abc123"
        @NotBlank String subreddit,
        @NotBlank String title,
        String body,                        // may be empty
        @PositiveOrZero int score,          // upvotes
        @PositiveOrZero int numComments,
        @NotNull OffsetDateTime createdUtc,
        @NotBlank String permalink,
        @NotNull List<String> pairHints     // e.g. ["BTC-EUR", "ETH-EUR"]; empty for generic posts
) {}
