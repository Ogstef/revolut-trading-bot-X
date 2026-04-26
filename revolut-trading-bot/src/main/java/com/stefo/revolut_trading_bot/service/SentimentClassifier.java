package com.stefo.revolut_trading_bot.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stefo.revolut_trading_bot.config.SentimentConfig;
import com.stefo.revolut_trading_bot.market.AnthropicClient;
import com.stefo.revolut_trading_bot.market.AnthropicClient.MessageResponse;
import com.stefo.revolut_trading_bot.market.AnthropicClient.Usage;
import com.stefo.revolut_trading_bot.model.dto.BudgetAssessment;
import com.stefo.revolut_trading_bot.model.dto.ClassificationResult;
import com.stefo.revolut_trading_bot.model.dto.ingest.RedditPostDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Classifies Reddit posts via Claude Haiku. Called by {@link RedditIngestService}.
 *
 * Three guarantees:
 *   1. <b>Budget-safe:</b> asks {@link LlmBudgetTracker#assess} before each batch.
 *      On breach, the batch is dropped and subsequent batches are not attempted.
 *   2. <b>Resilient:</b> if the API errors or returns unparseable JSON, that batch
 *      yields no results but the classifier does not throw — the caller still
 *      persists rows for the batches that succeeded.
 *   3. <b>Typed:</b> results are {@link ClassificationResult} records, keyed by
 *      the Reddit external id. No raw maps or JsonNodes returned.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SentimentClassifier {

    private static final String SYSTEM_PROMPT = """
            You are a sentiment classifier for cryptocurrency trading. You will receive \
            a JSON array of Reddit posts. For each post, return a JSON object with three \
            fields: id (the post id exactly as given), score (a decimal number in the \
            range [-1, 1], where -1 is strongly bearish, 0 is neutral, +1 is strongly bullish), \
            and reason (one short sentence explaining the score).

            Rules:
            - Output ONLY a JSON array. No prose, no code fences, no explanations outside the array.
            - One object per input post, in the same order.
            - Base the score on forward-looking market sentiment toward the mentioned coins, \
              not on the writer's politics, memes, or off-topic commentary.
            - Posts discussing fear, selling, drawdowns, hacks, or scams → negative score.
            - Posts discussing accumulation, breakouts, institutional adoption, or bullish \
              technicals → positive score.
            - Ambiguous, purely informational, or off-topic posts → score near 0.
            - Reason must be at most 120 characters.
            """;

    /** Rough estimate per batch — used for the pre-call budget assessment. */
    private static final BigDecimal ESTIMATED_COST_PER_BATCH_USD = new BigDecimal("0.005");

    /** Generous upper bound on output tokens per call. 20 posts * ~40 tokens each = 800. */
    private static final int MAX_OUTPUT_TOKENS = 1024;

    private final SentimentConfig config;
    private final AnthropicClient anthropic;
    private final LlmBudgetTracker budget;
    private final ObjectMapper objectMapper;

    /**
     * Classifies the given posts in {@code batch-size} chunks and returns a map
     * from external id → {@link ClassificationResult}. Posts that could not be
     * classified (budget, API failure, parse failure) are simply absent.
     */
    public Map<String, ClassificationResult> classify(List<RedditPostDto> posts) {
        if (posts == null || posts.isEmpty()) {
            return Map.of();
        }
        if (config.getClassifier().getAnthropicApiKey() == null
                || config.getClassifier().getAnthropicApiKey().isBlank()) {
            log.warn("[classifier] anthropic.api-key not configured — returning 0 classifications");
            return Map.of();
        }

        Map<String, ClassificationResult> out = new HashMap<>();
        int batchSize = Math.max(config.getClassifier().getBatchSize(), 1);

        for (int start = 0; start < posts.size(); start += batchSize) {
            List<RedditPostDto> batch = posts.subList(start, Math.min(start + batchSize, posts.size()));
            if (!approveSpend()) {
                log.warn("[classifier] budget cap reached — stopping after {} classifications", out.size());
                break;
            }
            out.putAll(classifyBatch(batch));
        }
        return out;
    }

    // ─── Private helpers ─────────────────────────────────────────────────

    private boolean approveSpend() {
        BudgetAssessment decision = budget.assess(ESTIMATED_COST_PER_BATCH_USD);
        if (decision.allowed()) {
            return true;
        }
        log.warn("[classifier] pre-call budget blocked: {} (dailySpent={} monthlySpent={})",
                decision.decision(), decision.dailySpentUsd(), decision.monthlySpentUsd());
        return false;
    }

    private Map<String, ClassificationResult> classifyBatch(List<RedditPostDto> batch) {
        String userPrompt = buildUserPrompt(batch);
        MessageResponse response;
        try {
            response = anthropic.createMessage(SYSTEM_PROMPT, userPrompt, MAX_OUTPUT_TOKENS);
        } catch (AnthropicClient.AnthropicException e) {
            log.warn("[classifier] Anthropic call failed for batch of {}: {}", batch.size(), e.getMessage());
            return Map.of();
        }

        recordActualSpend(response.usage(), batch.size());

        List<ClassificationItem> items = parseResponse(response.text());
        if (items.isEmpty()) {
            return Map.of();
        }

        Map<String, ClassificationResult> out = new HashMap<>(items.size());
        for (ClassificationItem item : items) {
            if (item.id() == null || item.score() == null) {
                continue;
            }
            BigDecimal clamped = clamp(item.score());
            out.put(item.id(), new ClassificationResult(item.id(), clamped, item.reason()));
        }
        return out;
    }

    private String buildUserPrompt(List<RedditPostDto> batch) {
        List<PostForPrompt> items = new ArrayList<>(batch.size());
        for (RedditPostDto post : batch) {
            items.add(new PostForPrompt(post.externalId(), post.title(), truncate(post.body(), 600)));
        }
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            log.warn("[classifier] user-prompt serialization failed: {}", e.getMessage());
            return "[]";
        }
    }

    private List<ClassificationItem> parseResponse(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String trimmed = stripCodeFences(raw).trim();
        try {
            ClassificationItem[] items = objectMapper.readValue(trimmed, ClassificationItem[].class);
            return List.of(items);
        } catch (JsonProcessingException e) {
            log.warn("[classifier] unparseable JSON from Haiku (len={}): {}",
                    trimmed.length(), e.getMessage());
            return List.of();
        }
    }

    private void recordActualSpend(Usage usage, int postCount) {
        if (usage == null) return;
        BigDecimal cost = AnthropicClient.costOf(usage);
        budget.recordSpend(cost, postCount);
        log.debug("[classifier] spent {} USD — in={} out={} cacheWrite={} cacheRead={}",
                cost, usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreation(), usage.cacheRead());
    }

    private static BigDecimal clamp(BigDecimal score) {
        return score.max(BigDecimal.valueOf(-1)).min(BigDecimal.ONE);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String stripCodeFences(String s) {
        String trimmed = s.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed;
    }

    // ─── Internal DTOs (not exposed) ─────────────────────────────────────

    /** Shape we send to the model — avoids leaking Reddit-internal fields like score/comments. */
    private record PostForPrompt(String id, String title, String body) {}

    /** Shape we parse back from the model's reply. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ClassificationItem(String id, BigDecimal score, String reason) {}
}
