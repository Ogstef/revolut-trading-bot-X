package com.stefo.revolut_trading_bot.scheduler

import spock.lang.Specification
import spock.lang.Subject

class BotStateServiceSpec extends Specification {

    @Subject
    BotStateService service = new BotStateService()

    def "bot is active by default on startup"() {
        expect:
        service.isActive()
    }

    def "stop() deactivates the bot"() {
        when:
        service.stop()

        then:
        !service.isActive()
    }

    def "resume() reactivates the bot after a stop"() {
        given:
        service.stop()

        when:
        service.resume()

        then:
        service.isActive()
    }

    def "multiple stop calls are idempotent"() {
        when:
        service.stop()
        service.stop()

        then:
        !service.isActive()
    }

    def "multiple resume calls are idempotent"() {
        given:
        service.stop()

        when:
        service.resume()
        service.resume()

        then:
        service.isActive()
    }

    def "stop then resume then stop leaves bot inactive"() {
        when:
        service.stop()
        service.resume()
        service.stop()

        then:
        !service.isActive()
    }
}
