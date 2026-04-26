package com.stefo.revolut_trading_bot.model.dto.ingest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public record CryptoPanicIngestRequest(
        @NotNull @Valid List<CryptoPanicPostDto> posts,
        @NotNull Instant scrapedAt
) {}
