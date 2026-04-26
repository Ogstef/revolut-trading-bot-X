package com.stefo.revolut_trading_bot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.stefo.revolut_trading_bot.model.dto.ingest.CryptoPanicIngestRequest
import com.stefo.revolut_trading_bot.model.dto.ingest.CryptoPanicPostDto
import com.stefo.revolut_trading_bot.model.dto.ingest.CryptoPanicVotesDto
import com.stefo.revolut_trading_bot.model.entity.SentimentSnapshot
import com.stefo.revolut_trading_bot.model.enums.SentimentSource
import com.stefo.revolut_trading_bot.repository.SentimentSnapshotRepository
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class CryptoPanicIngestServiceSpec extends Specification {

    SentimentSnapshotRepository repository = Mock()
    ObjectMapper mapper = new ObjectMapper()

    @Subject
    CryptoPanicIngestService service = new CryptoPanicIngestService(repository, mapper)

    def "derives correct score for pure bullish post"() {
        given:
        def votes = new CryptoPanicVotesDto(10, 0, 0, 0, 0)
        expect:
        CryptoPanicIngestService.deriveScore(votes).toPlainString() == "1.000"
    }

    def "derives correct score for pure bearish post"() {
        given:
        def votes = new CryptoPanicVotesDto(0, 10, 0, 0, 0)
        expect:
        CryptoPanicIngestService.deriveScore(votes).toPlainString() == "-1.000"
    }

    @Unroll
    def "derives #expected for positive=#pos negative=#neg"() {
        given:
        def votes = new CryptoPanicVotesDto(pos, neg, 0, 0, 0)
        expect:
        CryptoPanicIngestService.deriveScore(votes).toPlainString() == expected
        where:
        pos | neg | expected
         5  |  5  | "0.000"
         3  |  1  | "0.500"
         1  |  3  | "-0.500"
         0  |  0  | "0.000"
    }

    def "persists one snapshot per mapped pair"() {
        given:
        def post = new CryptoPanicPostDto(
                "777", "Title", "http://x",
                new CryptoPanicVotesDto(4, 1, 0, 0, 0),
                ["BTC", "ETH"],
                OffsetDateTime.now(ZoneOffset.UTC))
        def request = new CryptoPanicIngestRequest([post], Instant.now())

        repository.existsBySourceAndExternalId(SentimentSource.CRYPTOPANIC, "777") >> false

        when:
        def response = service.ingest(request)

        then:
        1 * repository.saveAll({ List<SentimentSnapshot> list ->
            list.size() == 2 &&
            list.collect { it.pair }.containsAll(["BTC-EUR", "ETH-EUR"]) &&
            list.every { it.source == SentimentSource.CRYPTOPANIC } &&
            list.every { it.externalId == "777" }
        })
        response.received() == 1
        response.accepted() == 1
        response.deduped() == 0
        response.filtered() == 0
    }

    def "skips posts already stored"() {
        given:
        def post = new CryptoPanicPostDto(
                "dup", "Title", "http://x",
                new CryptoPanicVotesDto(1, 0, 0, 0, 0),
                ["BTC"],
                OffsetDateTime.now(ZoneOffset.UTC))
        def request = new CryptoPanicIngestRequest([post], Instant.now())

        repository.existsBySourceAndExternalId(SentimentSource.CRYPTOPANIC, "dup") >> true

        when:
        def response = service.ingest(request)

        then:
        0 * repository.saveAll(_)
        response.received() == 1
        response.accepted() == 0
        response.deduped() == 1
    }

    def "filters posts whose currencies we don't trade"() {
        given:
        def post = new CryptoPanicPostDto(
                "x", "Doge news", "http://x",
                new CryptoPanicVotesDto(5, 0, 0, 0, 0),
                ["DOGE"],
                OffsetDateTime.now(ZoneOffset.UTC))
        def request = new CryptoPanicIngestRequest([post], Instant.now())

        repository.existsBySourceAndExternalId(_, _) >> false

        when:
        def response = service.ingest(request)

        then:
        0 * repository.saveAll(_)
        response.filtered() == 1
    }
}
