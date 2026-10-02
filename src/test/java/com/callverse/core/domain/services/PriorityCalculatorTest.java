package com.callverse.core.domain.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.domain.enums.ChurnRisk;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.Intent;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The queue position of a conversation: plain unit tests, no Spring, no database. */
class PriorityCalculatorTest {

    @Test
    @DisplayName("a low-risk mass-market customer with a balance question scores zero")
    void baseline() {
        assertThat(PriorityCalculator.score(ChurnRisk.LOW, CustomerSegment.MASS, Intent.BALANCE))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the three factors add up: HIGH churn 40 + PRIVATE 25 + FRAUD 30 = 95, the maximum")
    void factorsAdd() {
        assertThat(PriorityCalculator.score(ChurnRisk.HIGH, CustomerSegment.PRIVATE, Intent.FRAUD))
                .isEqualByComparingTo("95");
    }

    @Test
    @DisplayName("each factor on its own: MEDIUM churn 20, AFFLUENT 15, PROFESSIONAL 10, closure 20, card 10, credit 5")
    void eachFactor() {
        assertThat(PriorityCalculator.score(ChurnRisk.MEDIUM, CustomerSegment.MASS, Intent.OTHER)).isEqualByComparingTo("20");
        assertThat(PriorityCalculator.score(ChurnRisk.LOW, CustomerSegment.AFFLUENT, Intent.OTHER)).isEqualByComparingTo("15");
        assertThat(PriorityCalculator.score(ChurnRisk.LOW, CustomerSegment.PROFESSIONAL, Intent.OTHER)).isEqualByComparingTo("10");
        assertThat(PriorityCalculator.score(ChurnRisk.LOW, CustomerSegment.MASS, Intent.ACCOUNT_CLOSURE)).isEqualByComparingTo("20");
        assertThat(PriorityCalculator.score(ChurnRisk.LOW, CustomerSegment.MASS, Intent.CARD)).isEqualByComparingTo("10");
        assertThat(PriorityCalculator.score(ChurnRisk.LOW, CustomerSegment.MASS, Intent.CREDIT)).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("an unclassified contact (no intent yet) is scored on the customer alone")
    void unknownIntent() {
        assertThat(PriorityCalculator.score(ChurnRisk.HIGH, CustomerSegment.MASS, null)).isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("Amina (MEDIUM, AFFLUENT) calling about fraud outranks Lucas (LOW, MASS) asking about his balance")
    void ordering() {
        BigDecimal amina = PriorityCalculator.score(ChurnRisk.MEDIUM, CustomerSegment.AFFLUENT, Intent.FRAUD);
        BigDecimal lucas = PriorityCalculator.score(ChurnRisk.LOW, CustomerSegment.MASS, Intent.BALANCE);
        assertThat(amina).isGreaterThan(lucas);
    }

    @ParameterizedTest
    @EnumSource(Intent.class)
    @DisplayName("every score fits NUMERIC(6,2) with scale 2, so the column never rounds or overflows")
    void fitsTheColumn(Intent intent) {
        BigDecimal score = PriorityCalculator.score(ChurnRisk.HIGH, CustomerSegment.PRIVATE, intent);
        assertThat(score.scale()).isEqualTo(2);
        assertThat(score).isLessThan(new BigDecimal("10000"));
        assertThat(score.signum()).isNotNegative();
    }
}
