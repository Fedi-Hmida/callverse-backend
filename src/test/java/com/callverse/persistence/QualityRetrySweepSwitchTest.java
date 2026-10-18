package com.callverse.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** The suite runs with the scheduled Quality retry switched off: no background AI calls in tests. */
class QualityRetrySweepSwitchTest extends AbstractPersistenceTest {

    @Autowired ApplicationContext context;

    @Test
    void theRetrySweepIsOffInTests() {
        assertThat(context.containsBean("qualityRetrySweep")).isFalse();
    }
}
