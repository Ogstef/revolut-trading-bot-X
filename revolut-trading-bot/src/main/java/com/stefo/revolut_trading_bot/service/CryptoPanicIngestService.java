package com.stefo.revolut_trading_bot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stefo.revolut_trading_bot.model.dto.ingest.CryptoPanicIngestRequest;
import com.stefo.revolut_trading_bot.model.dto.ingest.CryptoPanicPostDto;
import com.stefo.revolut_trading_bot.model.dto.ingest.CryptoPanicVotesDto;
import com.stefo.revolut_trading_bot.model.dto.ingest.IngestResponse;
import com.stefo.revolut_trading_bot.model.entity.SentimentSnapshot;
import com.stefo.revolut_trading_bot.model.enums.SentimentSource;
import com.stefo.revolut_trading_bot.repository.SentimentSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles {@code POST /api/sentiment/ingest/cryptopanic}.
 *
 * No LLM involved — score is derived directly from the scraped vote counts:
 *   {@code score = (positive - negative) / max(positive + negative, 1)}  ∈ [-1, +1]
 *
 * One post may be tagged with multiple currencies (e.g. BTC + ETH); the service
 * emits one {@link SentimentSnapshot} row per {@code (post, pair)} so both
 * strategies pick it up via the existing windowed aggregate query.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoPanicIngestService {

    /** Currency ticker → canonical bot pair string. */
    private static final Map<String, String> TICKER_TO_PAIR = Map.of(
            "BTC", "BTC-EUR",
            "ETH", "ETH-EUR",
            "SOL", "SOL-EUR"
    );

    private final SentimentSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public IngestResponse ingest(CryptoPanicIngestRequest request) {
        List<CryptoPanicPostDto> posts = request.posts();
        int received = posts.size();
        int deduped  = 0;
        int filtered = 0;
        int accepted = 0;

        List<SentimentSnapshot> toSave = new ArrayList<>();
        for (CryptoPanicPostDto post : posts) {
            if (repository.existsBySourceAndExternalId(SentimentSource.CRYPTOPANIC, post.externalId())) {
                deduped++;
                continue;
            }

            List<String> pairs = mapCurrenciesToPairs(post.currencyCodes());
            if (pairs.isEmpty()) {
                filtered++;
                continue;
            }

            BigDecimal score = deriveScore(post.votes());
            int volume = post.votes().totalEngagement();
            String metadata = serializeMetadata(post);
            for (String pair : pairs) {
                toSave.add(snapshot(post, pair, score, volume, metadata));
            }
            accepted++;
        }

        if (!toSave.isEmpty()) {
            repository.saveAll(toSave);
        }
        log.info("[ingest/cryptopanic] received={} accepted={} deduped={} filtered={} rows={}",
                received, accepted, deduped, filtered, toSave.size());
        return new IngestResponse(received, accepted, deduped, filtered, 0);
    }

    // ─── Private helpers ─────────────────────────────────────────────────

    static BigDecimal deriveScore(CryptoPanicVotesDto votes) {
        int diff = votes.positive() - votes.negative();
        int base = Math.max(votes.positive() + votes.negative(), 1);
        return BigDecimal.valueOf(diff)
                .divide(BigDecimal.valueOf(base), 3, RoundingMode.HALF_UP)
                .max(BigDecimal.valueOf(-1))
                .min(BigDecimal.ONE);
    }

    private List<String> mapCurrenciesToPairs(List<String> tickers) {
        List<String> out = new ArrayList<>(tickers.size());
        for (String ticker : tickers) {
            String pair = TICKER_TO_PAIR.get(ticker.toUpperCase());
            if (pair != null && !out.contains(pair)) {
                out.add(pair);
            }
        }
        return out;
    }

    private SentimentSnapshot snapshot(CryptoPanicPostDto post,
                                       String pair,
                                       BigDecimal score,
                                       int volume,
                                       String metadata) {
        return SentimentSnapshot.builder()
                .pair(pair)
                .source(SentimentSource.CRYPTOPANIC)
                .externalId(post.externalId())
                .score(score)
                .volume(volume)
                .sampleSize(1)
                .metadata(metadata)
                .capturedAt(post.publishedAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime())
                .build();
    }

    private String serializeMetadata(CryptoPanicPostDto post) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "title", post.title(),
                    "url", post.url(),
                    "votes", post.votes()
            ));
        } catch (JsonProcessingException e) {
            log.warn("[ingest/cryptopanic] metadata serialization failed for {}: {}",
                    post.externalId(), e.getMessage());
            return null;
        }
    }
}
