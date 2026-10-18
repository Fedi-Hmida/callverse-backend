package com.callverse.core.application.features.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.features.simulation.queries.GetDigitalTwinRunsQueryHandler;
import com.callverse.core.application.interfaces.TwinRunStore;
import com.callverse.core.application.interfaces.TwinRunStore.TwinRun;
import com.callverse.core.application.interfaces.TwinRunStore.TwinScenario;
import com.callverse.core.domain.enums.LoadProfile;
import com.callverse.core.domain.enums.RunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetDigitalTwinRunsQueryHandlerTest {

    private final TwinRunStore store = mock(TwinRunStore.class);
    private final GetDigitalTwinRunsQueryHandler handler = new GetDigitalTwinRunsQueryHandler(store);
    private final UUID scenario = UUID.randomUUID();

    @Test
    void oneRunByIdOrNotFound() {
        TwinRun run = new TwinRun(UUID.randomUUID(), scenario, "THRESHOLD", 7, RunStatus.COMPLETED, null,
                Instant.EPOCH, Instant.EPOCH, null);
        when(store.find(run.runId())).thenReturn(Optional.of(run));

        assertThat(handler.byId(run.runId())).isEqualTo(run);
        assertThatThrownBy(() -> handler.byId(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theComparisonListsTheScenariosCompletedRuns() {
        when(store.scenario(scenario)).thenReturn(Optional.of(
                new TwinScenario(scenario, "Lundi", LoadProfile.LOW, 60, 8)));
        when(store.completedForScenario(scenario)).thenReturn(List.of());

        assertThat(handler.comparison(scenario)).isEmpty();
        verify(store).completedForScenario(scenario);
    }

    @Test
    void anUnknownScenarioHasNoComparison() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> handler.comparison(unknown)).isInstanceOf(ResourceNotFoundException.class);
        verify(store, never()).completedForScenario(unknown);
    }
}
