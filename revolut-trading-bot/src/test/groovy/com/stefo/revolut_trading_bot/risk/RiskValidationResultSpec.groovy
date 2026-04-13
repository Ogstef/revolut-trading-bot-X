package com.stefo.revolut_trading_bot.risk

import spock.lang.Specification

import java.math.BigDecimal

class RiskValidationResultSpec extends Specification {

    def "approved factory sets approved=true and positionSize"() {
        when:
        def result = RiskValidationResult.approved(BigDecimal.valueOf(200))

        then:
        result.approved()
        result.positionSizeEur() == BigDecimal.valueOf(200)
        result.reason() == "All risk checks passed"
    }

    def "rejected factory sets approved=false and reason"() {
        when:
        def result = RiskValidationResult.rejected("Max positions reached")

        then:
        !result.approved()
        result.reason() == "Max positions reached"
        result.positionSizeEur() == null
    }

    def "RiskStatus.anyCircuitBreakerTripped returns false when no breakers are on"() {
        given:
        def status = new RiskManager.RiskStatus(0, BigDecimal.ZERO, 0, false, false, false)

        expect:
        !status.anyCircuitBreakerTripped()
    }

    def "RiskStatus.anyCircuitBreakerTripped returns true when daily breaker is on"() {
        given:
        def status = new RiskManager.RiskStatus(0, BigDecimal.valueOf(-600), 0, true, false, false)

        expect:
        status.anyCircuitBreakerTripped()
    }

    def "RiskStatus.anyCircuitBreakerTripped returns true when consecutive breaker is on"() {
        given:
        def status = new RiskManager.RiskStatus(0, BigDecimal.ZERO, 5, false, true, false)

        expect:
        status.anyCircuitBreakerTripped()
    }

    def "RiskStatus.anyCircuitBreakerTripped returns true when both breakers are on"() {
        given:
        def status = new RiskManager.RiskStatus(0, BigDecimal.valueOf(-600), 5, true, true, false)

        expect:
        status.anyCircuitBreakerTripped()
    }
}
