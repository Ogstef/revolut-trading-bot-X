package com.stefo.revolut_trading_bot.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.stefo.revolut_trading_bot.config.SentimentConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Thin Claude Messages API client — typed request/response records, no SDK dependency.
 *
 * We use Spring's {@link RestClient} (already in the project via {@code FearGreedService})
 * rather than pulling in {@code com.anthropic:anthropic-java}. The surface we need is
 * exactly one endpoint, and a typed record-based client keeps dependencies lean.
 *
 * Prompt caching is enabled on the system block so the classification rubric is
 * read from Anthropic's cache on every call — the per-batch user turn is the only
 * part that gets freshly billed at the normal input rate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnthropicClient {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final SentimentConfig config;

    /**
     * Dedicated mapper in snake_case to match the Claude JSON contract.
     * {@code NON_NULL} is applied at the record level via {@code @JsonInclude} below,
     * avoiding Jackson's deprecated mapper-wide setter.
     */
    private final ObjectMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .build();

    private final RestClient restClient = RestClient.create();

    // ─── Public API ──────────────────────────────────────────────────────

    /**
     * One chat completion with a cached system prompt and a single user turn.
     * Returns the raw response so callers can pull out text content + token usage.
     *
     * @throws AnthropicException on any non-2xx / transport error. Callers typically
     *                            log and skip the batch rather than retry — the classifier
     *                            is not critical-path.
     */
    public MessageResponse createMessage(String systemPrompt, String userPrompt, int maxTokens) {
        SentimentConfig.Classifier cc = config.getClassifier();
        if (cc.getAnthropicApiKey() == null || cc.getAnthropicApiKey().isBlank()) {
            throw new AnthropicException("anthropic.api-key not configured");
        }

        MessageRequest request = new MessageRequest(
                cc.getModel(),
                maxTokens,
                List.of(new SystemBlock("text", systemPrompt, CacheControl.EPHEMERAL)),
                List.of(new Message("user", userPrompt))
        );

        try {
            return restClient.post()
                    .uri(ENDPOINT)
                    .header("x-api-key", cc.getAnthropicApiKey())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(mapper.writeValueAsBytes(request))
                    .retrieve()
                    .body(MessageResponse.class);
        } catch (HttpStatusCodeException e) {
            throw new AnthropicException(
                    "Anthropic API returned " + e.getStatusCode() + ": " + e.getResponseBodyAsString(),
                    e);
        } catch (Exception e) {
            throw new AnthropicException("Anthropic API call failed: " + e.getMessage(), e);
        }
    }

    // ─── Typed request / response DTOs ───────────────────────────────────

    /**
     * Wire format: {@code {"model": "...", "max_tokens": N, "system": [...], "messages": [...]}}.
     * Property names are snake_case via the dedicated mapper above.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageRequest(
            String model,
            int maxTokens,
            List<SystemBlock> system,
            List<Message> messages
    ) {}

    /** One system-prompt block. We always use a single cached text block. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SystemBlock(
            String type,                        // "text"
            String text,
            CacheControl cacheControl           // may be null for non-cached blocks
    ) {}

    public record CacheControl(String type) {
        /** The standard ephemeral cache — 5-min TTL on the Claude side. */
        public static final CacheControl EPHEMERAL = new CacheControl("ephemeral");
    }

    public record Message(
            String role,                        // "user" / "assistant"
            String content                      // single text turn
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageResponse(
            String id,
            List<ContentBlock> content,
            Usage usage
    ) {
        /** The concatenation of all text blocks — the assistant's reply as one string. */
        public String text() {
            if (content == null) return "";
            return content.stream()
                    .filter(b -> "text".equals(b.type()))
                    .map(ContentBlock::text)
                    .reduce("", (a, b) -> a + b);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContentBlock(String type, String text) {}

    /** Token usage from the response. Nullable fields default to 0 on the wire. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("input_tokens")              int inputTokens,
            @JsonProperty("output_tokens")             int outputTokens,
            @JsonProperty("cache_creation_input_tokens") Integer cacheCreationInputTokens,
            @JsonProperty("cache_read_input_tokens")     Integer cacheReadInputTokens
    ) {
        public int cacheCreation() { return cacheCreationInputTokens != null ? cacheCreationInputTokens : 0; }
        public int cacheRead()     { return cacheReadInputTokens     != null ? cacheReadInputTokens     : 0; }
    }

    // ─── Cost estimation ─────────────────────────────────────────────────

    /** Haiku 4.5 pricing — USD per 1M tokens. Update here if Anthropic changes pricing. */
    private static final BigDecimal INPUT_PRICE_PER_MTOK           = new BigDecimal("1.00");
    private static final BigDecimal OUTPUT_PRICE_PER_MTOK          = new BigDecimal("5.00");
    private static final BigDecimal CACHE_WRITE_PRICE_PER_MTOK     = new BigDecimal("1.25");
    private static final BigDecimal CACHE_READ_PRICE_PER_MTOK      = new BigDecimal("0.10");
    private static final BigDecimal ONE_MILLION                    = new BigDecimal("1000000");

    /** USD cost of a completed call, derived from reported token usage. */
    public static BigDecimal costOf(Usage usage) {
        BigDecimal input  = ratePer(usage.inputTokens(),      INPUT_PRICE_PER_MTOK);
        BigDecimal output = ratePer(usage.outputTokens(),     OUTPUT_PRICE_PER_MTOK);
        BigDecimal write  = ratePer(usage.cacheCreation(),    CACHE_WRITE_PRICE_PER_MTOK);
        BigDecimal read   = ratePer(usage.cacheRead(),        CACHE_READ_PRICE_PER_MTOK);
        return input.add(output).add(write).add(read).setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratePer(int tokens, BigDecimal pricePerMillion) {
        return pricePerMillion
                .multiply(BigDecimal.valueOf(tokens))
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
    }

    // ─── Exception ───────────────────────────────────────────────────────

    public static class AnthropicException extends RuntimeException {
        public AnthropicException(String message) { super(message); }
        public AnthropicException(String message, Throwable cause) { super(message, cause); }
    }
}
