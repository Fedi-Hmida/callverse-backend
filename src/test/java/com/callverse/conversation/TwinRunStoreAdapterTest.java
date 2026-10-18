package com.callverse.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.exceptions.SimulationRunAlreadyExistsException;
import com.callverse.core.application.interfaces.TwinRunStore;
import com.callverse.core.application.interfaces.TwinRunStore.RunKpis;
import com.callverse.core.application.interfaces.TwinRunStore.TwinRun;
import com.callverse.core.application.interfaces.TwinSimulator.TwinKpis;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.LoadProfile;
import com.callverse.core.domain.enums.RunStatus;
import com.callverse.persistence.AbstractPersistenceTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Digital twin runs in simulation_run and run_kpi, kept apart from conversation simulations. */
class TwinRunStoreAdapterTest extends AbstractPersistenceTest {

    @Autowired TwinRunStore store;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    private ConversationFixtures fx;
    private final Instant now = Instant.parse("2026-10-18T10:00:00Z");
    private static final TwinKpis KPIS = new TwinKpis(431, 402, 0.912345, 0.851234, 0.067288, 41.2345, 158.789, 12);

    @BeforeEach
    void setUp() {
        fx = new ConversationFixtures(em, tx);
    }

    @Test
    @DisplayName("a scenario reads as load, duration and staff")
    void scenario() {
        UUID scenario = fx.scenario("Mardi chargé", "SATURATED", 240, 12);

        assertThat(store.scenario(scenario)).hasValueSatisfying(s -> {
            assertThat(s.load()).isEqualTo(LoadProfile.SATURATED);
            assertThat(s.durationMinutes()).isEqualTo(240);
            assertThat(s.advisorCount()).isEqualTo(12);
        });
        assertThat(store.scenario(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("a completed run keeps the run_kpi columns, rounded to their precision")
    void completedRunKeepsItsKpis() {
        UUID scenario = fx.scenario("Lundi", "MEDIUM", 120, 8);
        TwinRun started = store.start(scenario, "THRESHOLD", 7, now);
        assertThat(started.status()).isEqualTo(RunStatus.RUNNING);

        TwinRun done = store.complete(started.runId(), KPIS, now.plusSeconds(1));

        RunKpis expected = new RunKpis(431, 0.8512, 0.0673, 41.23, 158.79);
        assertThat(done.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(done.kpis()).isEqualTo(expected);
        assertThat(store.find(started.runId())).hasValueSatisfying(run -> {
            assertThat(run.kpis()).isEqualTo(expected);
            assertThat(run.strategyCode()).isEqualTo("THRESHOLD");
            assertThat(run.endedAt()).isEqualTo(now.plusSeconds(1));
        });
    }

    @Test
    @DisplayName("a failed run keeps its generic message and no KPIs")
    void failedRun() {
        UUID scenario = fx.scenario("Lundi", "LOW", 60, 4);
        TwinRun started = store.start(scenario, "STATIC_FIFO", 3, now);

        TwinRun failed = store.fail(started.runId(), "Digital twin service unavailable.", now);

        assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.errorMessage()).isEqualTo("Digital twin service unavailable.");
        assertThat(failed.kpis()).isNull();
    }

    @Test
    @DisplayName("the same scenario, strategy and seed cannot run twice; an unknown strategy is refused")
    void uniquenessAndUnknownStrategy() {
        UUID scenario = fx.scenario("Lundi", "LOW", 60, 4);
        store.start(scenario, "THRESHOLD", 9, now);

        assertThatThrownBy(() -> store.start(scenario, "THRESHOLD", 9, now))
                .isInstanceOf(SimulationRunAlreadyExistsException.class);
        assertThatThrownBy(() -> store.start(scenario, "NO_SUCH_STRATEGY", 9, now))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("the comparison lists completed twin runs only; a conversation simulation is not a twin run")
    void comparisonAndSeparation() {
        UUID scenario = fx.scenario("Vendredi", "MEDIUM", 120, 8);
        UUID threshold = store.complete(store.start(scenario, "THRESHOLD", 1, now).runId(), KPIS, now).runId();
        UUID ppo = store.complete(store.start(scenario, "RL_PPO_V1", 1, now).runId(), KPIS, now).runId();
        store.fail(store.start(scenario, "STATIC_FIFO", 1, now).runId(), "down", now);
        UUID conversationRun = fx.simulationRunWithConversation(scenario, "STATIC_FIFO", 2, ConversationStatus.ACTIVE);

        assertThat(store.completedForScenario(scenario)).extracting(TwinRun::runId)
                .containsExactlyInAnyOrder(threshold, ppo);
        assertThat(store.find(conversationRun)).as("a conversation simulation is not a twin run").isEmpty();
    }
}
