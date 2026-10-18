package com.callverse.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.callverse.core.application.features.simulation.commands.RunConversationSimulationCommandHandler;
import com.callverse.core.application.interfaces.ConversationSimulationStore.SimulationSetup;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.SimulatorProfile;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

class ConversationSimulationDispatcherTest {

    private static final UUID RUN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void schedulesOnlyOneInFlightTaskPerRunAndReleasesItAfterCompletion() {
        RunConversationSimulationCommandHandler handler = mock(RunConversationSimulationCommandHandler.class);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        TaskExecutor executor = queued::set;
        ConversationSimulationDispatcher dispatcher = new ConversationSimulationDispatcher(handler, executor);

        assertThat(dispatcher.schedule(RUN_ID, setup())).isTrue();
        assertThat(dispatcher.schedule(RUN_ID, setup())).isFalse();
        verifyNoInteractions(handler);

        queued.get().run();
        verify(handler).handle(RUN_ID, setup());
        assertThat(dispatcher.schedule(RUN_ID, setup())).isTrue();
    }

    @Test
    void reportsWorkerRejectionWithoutThrowingFromTheRequestThread() {
        RunConversationSimulationCommandHandler handler = mock(RunConversationSimulationCommandHandler.class);
        TaskExecutor executor = task -> {
            throw new TaskRejectedException("worker queue full");
        };
        ConversationSimulationDispatcher dispatcher = new ConversationSimulationDispatcher(handler, executor);

        assertThat(dispatcher.schedule(RUN_ID, setup())).isFalse();
        verifyNoInteractions(handler);
    }

    private static SimulationSetup setup() {
        return new SimulationSetup(
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                "STATIC_FIFO",
                1L,
                SimulatorProfile.CALME,
                "Comprendre un débit",
                50,
                50,
                2,
                CustomerSegment.MASS,
                1);
    }
}
