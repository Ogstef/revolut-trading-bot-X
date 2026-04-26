package com.stefo.revolut_trading_bot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.stefo.revolut_trading_bot.config.SentimentConfig
import com.stefo.revolut_trading_bot.model.dto.ClassificationResult
import com.stefo.revolut_trading_bot.model.dto.ingest.RedditIngestRequest
import com.stefo.revolut_trading_bot.model.dto.ingest.RedditPostDto
import com.stefo.revolut_trading_bot.model.entity.SentimentSnapshot
import com.stefo.revolut_trading_bot.model.enums.SentimentSource
import com.stefo.revolut_trading_bot.repository.SentimentSnapshotRepository
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class RedditIngestServiceSpec extends Specification {

    SentimentConfig config                  = new SentimentConfig()
    SentimentSnapshotRepository repository  = Mock()
    SentimentClassifier classifier          = Mock()
    ObjectMapper mapper                     = new ObjectMapper()

    @Subject
    RedditIngestService service

    def setup() {
        config.redditFilters.minScore    = 5
        config.redditFilters.minComments = 3
        config.redditFilters.maxAgeHours = 24
        service = new RedditIngestService(config, repository, classifier, mapper)
    }

    def "drops posts below min-score"() {
        given:
        def post = buildPost("t3_low", 3, 10)       // below min-score 5
        def req = new RedditIngestRequest([post], Instant.now())
        repository.existsBySourceAndExternalId(_, _) >> false

        when:
        def response = service.ingest(req)

        then:
        0 * classifier.classify(_)
        0 * repository.saveAll(_)
        response.filtered() == 1
        response.accepted() == 0
    }

    def "drops posts below min-comments"() {
        given:
        def post = buildPost("t3_quiet", 100, 0)
        def req = new RedditIngestRequest([post], Instant.now())
        repository.existsBySourceAndExternalId(_, _) >> false

        when:
        def response = service.ingest(req)

        then:
        response.filtered() == 1
    }

    def "dedups posts already in the DB"() {
        given:
        def post = buildPost("t3_dup", 50, 10)
        def req = new RedditIngestRequest([post], Instant.now())
        repository.existsBySourceAndExternalId(SentimentSource.REDDIT, "t3_dup") >> true

        when:
        def response = service.ingest(req)

        then:
        0 * classifier.classify(_)
        response.deduped() == 1
        response.accepted() == 0
    }

    def "persists one row per pair-hint when classified"() {
        given:
        def post = buildPost("t3_ok", 100, 50, ["BTC-EUR", "ETH-EUR"])
        def req = new RedditIngestRequest([post], Instant.now())
        repository.existsBySourceAndExternalId(_, _) >> false
        classifier.classify(_) >> [
                "t3_ok": new ClassificationResult("t3_ok", new BigDecimal("0.600"), "bullish BTC")
        ]

        when:
        def response = service.ingest(req)

        then:
        1 * repository.saveAll({ List<SentimentSnapshot> list ->
            list.size() == 2 &&
            list.collect { it.pair }.containsAll(["BTC-EUR", "ETH-EUR"]) &&
            list.every { it.source == SentimentSource.REDDIT } &&
            list.every { it.score.toPlainString() == "0.600" }
        })
        response.accepted() == 1
        response.classified() == 1
    }

    def "writes pair='ALL' for posts with no pair-hints"() {
        given:
        def post = buildPost("t3_generic", 100, 50, [])
        def req = new RedditIngestRequest([post], Instant.now())
        repository.existsBySourceAndExternalId(_, _) >> false
        classifier.classify(_) >> [
                "t3_generic": new ClassificationResult("t3_generic", new BigDecimal("0.100"), "neutral")
        ]

        when:
        service.ingest(req)

        then:
        1 * repository.saveAll({ List<SentimentSnapshot> list ->
            list.size() == 1 && list[0].pair == "ALL"
        })
    }

    def "does not persist posts classifier dropped"() {
        given:
        def post = buildPost("t3_dropped", 100, 50, ["BTC-EUR"])
        def req = new RedditIngestRequest([post], Instant.now())
        repository.existsBySourceAndExternalId(_, _) >> false
        classifier.classify(_) >> [:]   // budget / API / parse failure

        when:
        def response = service.ingest(req)

        then:
        0 * repository.saveAll(_)
        response.classified() == 0
        response.accepted() == 0
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static RedditPostDto buildPost(String id, int score, int comments, List<String> pairs = ["BTC-EUR"]) {
        return new RedditPostDto(
                id,
                "CryptoCurrency",
                "title",
                "body",
                score,
                comments,
                OffsetDateTime.now(ZoneOffset.UTC),
                "/r/CryptoCurrency/comments/" + id,
                pairs
        )
    }
}
