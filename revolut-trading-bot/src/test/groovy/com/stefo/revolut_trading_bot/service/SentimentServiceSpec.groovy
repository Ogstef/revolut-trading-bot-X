package com.stefo.revolut_trading_bot.service

import com.stefo.revolut_trading_bot.config.SentimentConfig
import com.stefo.revolut_trading_bot.model.dto.SentimentAggregate
import com.stefo.revolut_trading_bot.model.enums.SentimentSource
import com.stefo.revolut_trading_bot.repository.SentimentSnapshotRepository
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal
import java.time.LocalDateTime

class SentimentServiceSpec extends Specification {

    SentimentConfig config = new SentimentConfig()
    SentimentSnapshotRepository repository = Mock()

    @Subject
    SentimentService service

    def setup() {
        def window1h = new SentimentConfig.Window()
        window1h.minutes = 240
        window1h.redditMinSample = 10
        window1h.cryptopanicMinSample = 5
        window1h.combinedMinSample = 10
        config.windows["1h"] = window1h

        service = new SentimentService(config, repository)
    }

    def "returns non-signal score below Reddit min-sample"() {
        given:
        repository.aggregate("BTC-EUR", SentimentSource.REDDIT, _ as LocalDateTime) >>
                new SentimentAggregate(new BigDecimal("0.42"), 80L, 5L)  // n=5 < min=10

        when:
        def score = service.scoreFor("BTC-EUR", "1h", SentimentSource.REDDIT)

        then:
        !score.hasSignal()
        score.score() == null
        score.sampleSize() == 5
        score.minSample() == 10
    }

    def "returns typed score with sufficient sample"() {
        given:
        repository.aggregate("BTC-EUR", SentimentSource.REDDIT, _ as LocalDateTime) >>
                new SentimentAggregate(new BigDecimal("0.621"), 200L, 25L)

        when:
        def score = service.scoreFor("BTC-EUR", "1h", SentimentSource.REDDIT)

        then:
        score.hasSignal()
        score.score().toPlainString() == "0.621"
        score.sampleSize() == 25
        score.volume() == 200
    }

    def "caches within 60s — second call does NOT hit the repo"() {
        given:
        repository.aggregate("BTC-EUR", SentimentSource.CRYPTOPANIC, _ as LocalDateTime) >>
                new SentimentAggregate(new BigDecimal("0.30"), 100L, 20L)

        when:
        service.scoreFor("BTC-EUR", "1h", SentimentSource.CRYPTOPANIC)
        service.scoreFor("BTC-EUR", "1h", SentimentSource.CRYPTOPANIC)

        then:
        1 * repository.aggregate("BTC-EUR", SentimentSource.CRYPTOPANIC, _)
    }

    def "COMBINED blends Reddit + CryptoPanic sample-weighted"() {
        given:
        // Reddit score +0.8 over 80 volume; CryptoPanic score -0.4 over 20 volume.
        // weighted = (0.8*80 + -0.4*20) / 100 = (64 - 8) / 100 = 0.560
        repository.aggregate("BTC-EUR", SentimentSource.REDDIT, _ as LocalDateTime) >>
                new SentimentAggregate(new BigDecimal("0.800"), 80L, 20L)
        repository.aggregate("BTC-EUR", SentimentSource.CRYPTOPANIC, _ as LocalDateTime) >>
                new SentimentAggregate(new BigDecimal("-0.400"), 20L, 10L)

        when:
        def score = service.scoreFor("BTC-EUR", "1h", SentimentSource.COMBINED)

        then:
        score.hasSignal()
        score.score().toPlainString() == "0.560"
        score.components().size() == 2
        !score.componentsAgree()         // signs differ
    }

    def "COMBINED detects agreement when both positive"() {
        given:
        repository.aggregate("BTC-EUR", SentimentSource.REDDIT, _ as LocalDateTime) >>
                new SentimentAggregate(new BigDecimal("0.30"), 50L, 20L)
        repository.aggregate("BTC-EUR", SentimentSource.CRYPTOPANIC, _ as LocalDateTime) >>
                new SentimentAggregate(new BigDecimal("0.20"), 50L, 10L)

        when:
        def score = service.scoreFor("BTC-EUR", "1h", SentimentSource.COMBINED)

        then:
        score.componentsAgree()
    }

    def "returns empty score for unknown interval"() {
        when:
        def score = service.scoreFor("BTC-EUR", "3m", SentimentSource.REDDIT)

        then:
        0 * repository.aggregate(_, _, _)
        !score.hasSignal()
    }
}
