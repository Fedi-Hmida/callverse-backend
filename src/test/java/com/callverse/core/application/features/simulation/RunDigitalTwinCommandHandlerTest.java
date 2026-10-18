package com.callverse.core.application.features.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.exceptions.SimulationRunAlreadyExistsException;
import com.callverse.core.application.exceptions.WorkforceServiceUnavailableException;
import com.callverse.core.application.features.simulation.commands.RunDigitalTwinCommand;
import com.callverse.core.application.features.simulation.commands.RunDigitalTwinCommandHandler;
import com.callverse.core.application.interfaces.TwinRunStore;
import com.callverse.core.application.interfaces.TwinRunStore.RunKpis;
import com.callverse.core.application.interfaces.TwinRunStore.TwinRun;
import com.callverse.core.application.interfaces.TwinRunStore.TwinScenario;
import com.callverse.core.application.interfaces.TwinSimulator;
import com.callverse.core.application.interfaces.TwinSimulator.TwinKpis;
import com.callverse.core.application.interfaces.TwinSimulator.TwinRunSpec;
import com.callverse.core.domain.enums.LoadProfile;
import com.callverse.core.domain.enums.RunStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One digital twin run: the scenario becomes a simulated day, the KPIs are kept, failures end the run. */
class RunDigitalTwinCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final UUID SCENARIO = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final TwinKpis KPIS = new TwinKpis(120, 110, 0.9, 0.8, 0.08, 42.0, 160.0, 5);
    private static final RunKpis STORED = new RunKpis(120, 0.8, 0.08, 42.0, 160.0);

    private final TwinRunStore store = mock(TwinRunStore.class);
    private final TwinSimulator simulator = mock(TwinSimulator.class);
    private final RunDigitalTwinCommandHandler handler =
            new RunDigitalTwinCommandHandler(store, simulator, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(store.scenario(SCENARIO)).thenReturn(Optional.of(
                new TwinScenario(SCENARIO, "Mardi chargé", LoadProfile.MEDIUM, 120, 10)));
        when(store.start(eq(SCENARIO), any(), anyLong(), eq(NOW))).thenReturn(run(RunStatus.RUNNING, null));
        when(store.complete(RUN, KPIS, NOW)).thenReturn(run(RunStatus.COMPLETED, STORED));
    }

    @Test
    void theScenarioBecomesASimulatedDayAndTheKpisAreKept() {
        when(simulator.run(new TwinRunSpec("PPO", 7, LoadProfile.MEDIUM, 120, 2))).thenReturn(KPIS);

        TwinRun result = handler.handle(new RunDigitalTwinCommand(SCENARIO, "RL_PPO_V1", 7));

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.kpis()).isEqualTo(STORED);
        verify(store).complete(RUN, KPIS, NOW);
    }

    @Test
    void eachSkillKeepsAtLeastOneAdvisorAndBaselinesKeepTheirName() {
        when(store.scenario(SCENARIO)).thenReturn(Optional.of(
                new TwinScenario(SCENARIO, "Équipe réduite", LoadProfile.SATURATED, 60, 2)));
        when(simulator.run(any())).thenReturn(KPIS);

        handler.handle(new RunDigitalTwinCommand(SCENARIO, "THRESHOLD", 1));
        handler.handle(new RunDigitalTwinCommand(SCENARIO, "STATIC_FIFO", 2));

        verify(simulator).run(new TwinRunSpec("THRESHOLD", 1, LoadProfile.SATURATED, 60, 1));
        verify(simulator).run(new TwinRunSpec("STATIC_FIFO", 2, LoadProfile.SATURATED, 60, 1));
    }

    @Test
    void anUnknownScenarioStartsNothing() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> handler.handle(new RunDigitalTwinCommand(unknown, "THRESHOLD", 1)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(store, never()).start(any(), any(), anyLong(), any());
    }

    @Test
    void aStrategyTheTwinCannotPlayIsRefusedBeforeStarting() {
        assertThatThrownBy(() -> handler.handle(new RunDigitalTwinCommand(SCENARIO, "HUMAN_GUESS", 1)))
                .isInstanceOf(InvalidRequestException.class);
        verify(store, never()).start(any(), any(), anyLong(), any());
    }

    @Test
    void aScenarioTheTwinCannotSimulateIsRefusedBeforeStarting() {
        for (TwinScenario impossible : java.util.List.of(
                new TwinScenario(SCENARIO, "Deux jours", LoadProfile.LOW, 2000, 8),
                new TwinScenario(SCENARIO, "Armée", LoadProfile.LOW, 60, 400))) {
            when(store.scenario(SCENARIO)).thenReturn(Optional.of(impossible));

            assertThatThrownBy(() -> handler.handle(new RunDigitalTwinCommand(SCENARIO, "THRESHOLD", 1)))
                    .isInstanceOf(InvalidRequestException.class);
        }
        verify(store, never()).start(any(), any(), anyLong(), any());
        verify(simulator, never()).run(any());
    }

    @Test
    void aRunThatCannotBeSavedEndsFailedInsteadOfStayingRunning() {
        when(simulator.run(any())).thenReturn(KPIS);
        when(store.complete(RUN, KPIS, NOW)).thenThrow(new IllegalStateException("database blip"));

        assertThatThrownBy(() -> handler.handle(new RunDigitalTwinCommand(SCENARIO, "THRESHOLD", 7)))
                .isInstanceOf(IllegalStateException.class);
        verify(store).fail(RUN, "Digital twin result could not be saved.", NOW);
    }

    @Test
    void aRepeatedRunIsRefusedAndTheTwinIsNotCalled() {
        when(store.start(eq(SCENARIO), eq("THRESHOLD"), eq(7L), eq(NOW)))
                .thenThrow(new SimulationRunAlreadyExistsException(SCENARIO, "THRESHOLD", 7));

        assertThatThrownBy(() -> handler.handle(new RunDigitalTwinCommand(SCENARIO, "THRESHOLD", 7)))
                .isInstanceOf(SimulationRunAlreadyExistsException.class);
        verify(simulator, never()).run(any());
    }

    @Test
    void aFailedTwinMarksTheRunFailedInsteadOfLeavingItRunning() {
        when(simulator.run(any())).thenThrow(new IllegalStateException("connection refused 10.0.0.5"));

        assertThatThrownBy(() -> handler.handle(new RunDigitalTwinCommand(SCENARIO, "THRESHOLD", 7)))
                .isInstanceOf(WorkforceServiceUnavailableException.class);
        verify(store).fail(RUN, "Digital twin service unavailable or returned an invalid result.", NOW);
        verify(store, never()).complete(any(), any(), any());
    }

    private static TwinRun run(RunStatus status, RunKpis kpis) {
        return new TwinRun(RUN, SCENARIO, "RL_PPO_V1", 7, status, null, NOW, null, kpis);
    }
}
