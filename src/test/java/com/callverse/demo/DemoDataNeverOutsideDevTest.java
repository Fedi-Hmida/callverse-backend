package com.callverse.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.persistence.AbstractPersistenceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The second gate on demo data: even with the flag switched on, a non-dev profile never loads it.
 * A flag copied into a production environment by mistake must not put demo customers there.
 */
@ActiveProfiles("prod")
@TestPropertySource(properties = "callverse.demo-data.enabled=true")
class DemoDataNeverOutsideDevTest extends AbstractPersistenceTest {

    @Autowired ApplicationContext context;

    @Test
    @DisplayName("prod with the flag on still has no demo loader")
    void flagAloneIsNotEnough() {
        assertThat(context.getBeansOfType(ApplicationRunner.class).values())
                .noneMatch(runner -> runner.getClass().getName().contains("DemoDataLoader"));
    }
}
