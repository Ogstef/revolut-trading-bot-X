package com.stefo.revolut_trading_bot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stefo.revolut_trading_bot.config.SentimentConfig;
import com.stefo.revolut_trading_bot.model.dto.ClassificationResult;
import com.stefo.revolut_trading_bot.model.dto.ingest.IngestResponse;
import com.stefo.revolut_trading_bot.model.dto.ingest.RedditIngestRequest;
import com.stefo.revolut_trading_bot.model.dto.ingest.RedditPostDto;
import com.stefo.revolut_trading_bot.model.entity.SentimentSnapshot;
import com.stefo.revolut_trading_bot.model.enums.SentimentSource;
import com.stefo.revolut_trading_bot.repository.SentimentSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles {@code POST /api/sentiment/ingest/reddit}.
 *
 * Pipeline:
 *   1. Dedup: skip posts already present by {@code (source=REDDIT, external_id)}.
 *   2. Filter: drop posts below {@code reddit-filters.min-score / min-comments} or older
 *      than {@code max-age-hours} — these would just burn LLM budget for noise.
 *   3. Classify: delegate to {@link SentimentClassifier}. Budget-gated; may
 *      classify partially or not at all when the cap is close.
 *   4. Persist: one {@link SentimentSnapshot} per (post × pair-hint). Posts with
 *      no pair-hint are written with {@code pair="ALL"} so the global aggregate
 *      query still picks them up.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedditIngestService {

    private static final String PAIR_ALL = "ALL";

    private final SentimentConfig config;
    private final SentimentSnapshotRepository repository;
    private final SentimentClassifier classifier;
    private final ObjectMapper objectMapper;

    @Transactional
    public IngestResponse ingest(RedditIngestRequest request) {
        List<RedditPostDto> posts = request.posts();
        int received = posts.size();

        List<RedditPostDto> notDuped = dropDuplicates(posts);
        int deduped = received - notDuped.size();

        List<RedditPostDto> allowed = applyFilters(notDuped);
        int filtered = notDuped.size() - allowed.size();

        // Short-circuit: nothing passed filters — don't invoke classifier, don't touch repo.
        if (allowed.isEmpty()) {
            log.info("[ingest/reddit] received={} deduped={} filtered={} — nothing to classify",
                    received, deduped, filtered);
            return new IngestResponse(received, 0, deduped, filtered, 0);
        }

        Map<String, ClassificationResult> classifications = classifier.classify(allowed);
        int classified = classifications.size();

        List<SentimentSnapshot> toSave = new ArrayList<>();
        for (RedditPostDto post : allowed) {
            ClassificationResult result = classifications.get(post.externalId());
            if (result == null) {
                continue;   // classifier dropped it (budget / API / parse failure) — no row
            }
            toSave.addAll(toSnapshots(post, result));
        }
        if (!toSave.isEmpty()) {
            repository.saveAll(toSave);
        }

        int accepted = classified;
        log.info("[ingest/reddit] received={} accepted={} deduped={} filtered={} classified={} rows={}",
                received, accepted, deduped, filtered, classified, toSave.size());
        return new IngestResponse(received, accepted, deduped, filtered, classified);
    }

    // ─── Pipeline stages ─────────────────────────────────────────────────

    private List<RedditPostDto> dropDuplicates(List<RedditPostDto> posts) {
        List<RedditPostDto> out = new ArrayList<>(posts.size());
        for (RedditPostDto post : posts) {
            if (!repository.existsBySourceAndExternalId(SentimentSource.REDDIT, post.externalId())) {
                out.add(post);
            }
        }
        return out;
    }

    private List<RedditPostDto> applyFilters(List<RedditPostDto> posts) {
        SentimentConfig.RedditFilters f = config.getRedditFilters();
        Instant cutoff = Instant.now().minus(Duration.ofHours(f.getMaxAgeHours()));
        List<RedditPostDto> out = new ArrayList<>(posts.size());
        for (RedditPostDto post : posts) {
            if (post.score()        < f.getMinScore())     continue;
            if (post.numComments()  < f.getMinComments())  continue;
            if (post.createdUtc().toInstant().isBefore(cutoff)) continue;
            out.add(post);
        }
        return out;
    }

    private List<SentimentSnapshot> toSnapshots(RedditPostDto post, ClassificationResult result) {
        int volume = Math.max(post.score(), 1);
        String metadata = serializeMetadata(post, result);
        List<String> pairs = post.pairHints().isEmpty() ? List.of(PAIR_ALL) : post.pairHints();
        List<SentimentSnapshot> out = new ArrayList<>(pairs.size());
        for (String pair : pairs) {
            out.add(SentimentSnapshot.builder()
                    .pair(pair)
                    .source(SentimentSource.REDDIT)
                    .externalId(post.externalId())
                    .score(result.score())
                    .volume(volume)
                    .sampleSize(1)
                    .metadata(metadata)
                    .capturedAt(post.createdUtc().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime())
                    .build());
        }
        return out;
    }

    private String serializeMetadata(RedditPostDto post, ClassificationResult result) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "subreddit", post.subreddit(),
                    "title",     post.title(),
                    "permalink", post.permalink(),
                    "reason",    result.reason() != null ? result.reason() : ""
            ));
        } catch (JsonProcessingException e) {
            log.warn("[ingest/reddit] metadata serialization failed for {}: {}",
                    post.externalId(), e.getMessage());
            return null;
        }
    }
}
