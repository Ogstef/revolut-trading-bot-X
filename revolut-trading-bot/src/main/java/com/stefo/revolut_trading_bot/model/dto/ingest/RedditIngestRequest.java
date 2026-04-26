package com.stefo.revolut_trading_bot.model.dto.ingest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * Batch payload pushed by the scraper to {@code POST /api/sentiment/ingest/reddit}.
 *
 * {@link #posts} may be empty (harmless heartbeat); oversize batches are
 * rejected with 413 before this DTO is bound.
 */
public record RedditIngestRequest(
        @NotNull @Valid List<RedditPostDto> posts,
        @NotNull Instant scrapedAt
) {}
