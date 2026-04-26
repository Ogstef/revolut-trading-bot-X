package com.stefo.revolut_trading_bot.controller;

import com.stefo.revolut_trading_bot.config.SentimentConfig;
import com.stefo.revolut_trading_bot.model.dto.ingest.CryptoPanicIngestRequest;
import com.stefo.revolut_trading_bot.model.dto.ingest.IngestResponse;
import com.stefo.revolut_trading_bot.model.dto.ingest.RedditIngestRequest;
import com.stefo.revolut_trading_bot.service.CryptoPanicIngestService;
import com.stefo.revolut_trading_bot.service.RedditIngestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingest endpoints — the scraper microservice POSTs here.
 *
 * Auth is handled upstream by {@link com.stefo.revolut_trading_bot.config.security.IngestTokenFilter}
 * (enabled-check + Bearer token). By the time a handler is invoked, the request is authenticated
 * and the pipeline is enabled.
 *
 * The controller's own job is batch-size validation (413) and thin delegation to the services.
 */
@Slf4j
@RestController
@RequestMapping("/api/sentiment/ingest")
@RequiredArgsConstructor
public class SentimentIngestController {

    private final SentimentConfig config;
    private final RedditIngestService redditIngestService;
    private final CryptoPanicIngestService cryptoPanicIngestService;

    @PostMapping("/reddit")
    public ResponseEntity<IngestResponse> ingestReddit(@Valid @RequestBody RedditIngestRequest request) {
        int size = request.posts().size();
        if (size > config.getIngest().getMaxBatchSize()) {
            log.warn("[ingest/reddit] oversize batch rejected: {} > {}",
                    size, config.getIngest().getMaxBatchSize());
            return ResponseEntity.status(413).build();     // Payload Too Large
        }
        return ResponseEntity.ok(redditIngestService.ingest(request));
    }

    @PostMapping("/cryptopanic")
    public ResponseEntity<IngestResponse> ingestCryptoPanic(
            @Valid @RequestBody CryptoPanicIngestRequest request) {
        int size = request.posts().size();
        if (size > config.getIngest().getMaxBatchSize()) {
            log.warn("[ingest/cryptopanic] oversize batch rejected: {} > {}",
                    size, config.getIngest().getMaxBatchSize());
            return ResponseEntity.status(413).build();
        }
        return ResponseEntity.ok(cryptoPanicIngestService.ingest(request));
    }
}
