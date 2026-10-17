package com.callverse.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.callverse.core.application.features.conversation.commands.AdvisorTurnCommand;
import com.callverse.core.application.features.conversation.commands.AdvisorTurnCommandHandler;
import com.callverse.core.application.interfaces.ConversationEvent;
import com.callverse.core.application.interfaces.ConversationMessages.MessageRecord;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.MessageSender;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

/**
 * The dispatcher coalesces customer messages per conversation: never two turns in flight, and the
 * latest customer message is always the one answered, even when it arrives mid-turn.
 */
class AdvisorTurnDispatcherTest {

    private static final UUID CONVERSATION = UUID.randomUUID();

    private final AdvisorTurnCommandHandler handler = mock(AdvisorTurnCommandHandler.class);
    private final Deque<Runnable> queued = new ArrayDeque<>();
    private final TaskExecutor manualExecutor = queued::add;
    private final List<Long> handled = new ArrayList<>();
    private AdvisorTurnDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(handler.enabled()).thenReturn(true);
        doAnswer(invocation -> {
            handled.add(invocation.<AdvisorTurnCommand>getArgument(0).triggeringCustomerMessageId());
            return null;
        }).when(handler).handle(any());
        dispatcher = new AdvisorTurnDispatcher(handler, manualExecutor);
    }

    @Test
    void aMessageArrivingWhileATurnIsQueuedIsCoalescedIntoTheLatest() {
        dispatcher.dispatch(customerMessage(10));
        dispatcher.dispatch(customerMessage(11));

        assertThat(queued).as("one worker per conversation").hasSize(1);
        runAll();

        assertThat(handled).containsExactly(11L);
    }

    @Test
    void aMessageArrivingDuringTheAiCallIsAnsweredAfterIt() {
        doAnswer(invocation -> {
            long id = invocation.<AdvisorTurnCommand>getArgument(0).triggeringCustomerMessageId();
            handled.add(id);
            if (id == 10) {
                dispatcher.dispatch(customerMessage(11)); // the customer writes again mid-call
            }
            return null;
        }).when(handler).handle(any());

        dispatcher.dispatch(customerMessage(10));
        runAll();

        assertThat(handled).containsExactly(10L, 11L);
    }

    @Test
    void aBusyConversationGoesToTheBackOfTheQueueSoOthersAreNotStarved() {
        UUID other = UUID.randomUUID();
        List<String> order = new ArrayList<>();
        doAnswer(invocation -> {
            AdvisorTurnCommand command = invocation.getArgument(0);
            boolean first = command.conversationId().equals(CONVERSATION);
            order.add((first ? "A" : "B") + command.triggeringCustomerMessageId());
            if (first && command.triggeringCustomerMessageId() == 10) {
                dispatcher.dispatch(customerMessage(11)); // A keeps writing during its own turn
            }
            return null;
        }).when(handler).handle(any());

        dispatcher.dispatch(customerMessage(10));
        dispatcher.dispatch(ConversationEvent.messagePosted(
                new MessageRecord(20, other, MessageSender.CUSTOMER, "B", Instant.now()), ConversationStatus.ACTIVE));
        runAll();

        assertThat(order).containsExactly("A10", "B20", "A11");
    }

    @Test
    void aRejectedTurnKeepsTheMessageForTheNextDispatch() {
        java.util.concurrent.atomic.AtomicBoolean reject = new java.util.concurrent.atomic.AtomicBoolean(true);
        AdvisorTurnDispatcher saturated = new AdvisorTurnDispatcher(handler, task -> {
            if (reject.get()) {
                throw new java.util.concurrent.RejectedExecutionException("saturated");
            }
            queued.add(task);
        });

        saturated.dispatch(customerMessage(10));
        reject.set(false);
        saturated.dispatch(customerMessage(9)); // an older event delivered late still wakes the worker
        runAll();

        assertThat(handled).containsExactly(10L);
    }

    @Test
    void anErrorInATurnStillReleasesTheConversation() {
        doAnswer(invocation -> {
            throw new AssertionError("simulated Error");
        }).when(handler).handle(any());
        dispatcher.dispatch(customerMessage(10));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> queued.poll().run()).isInstanceOf(Error.class);
        dispatcher.dispatch(customerMessage(11));

        assertThat(queued).as("the conversation is free again").hasSize(1);
    }

    @Test
    void aFinishedConversationAcceptsANewTurn() {
        dispatcher.dispatch(customerMessage(10));
        runAll();
        dispatcher.dispatch(customerMessage(12));
        runAll();

        assertThat(handled).containsExactly(10L, 12L);
    }

    @Test
    void aFailedTurnDoesNotBlockTheConversation() {
        doAnswer(invocation -> {
            throw new IllegalStateException("AI down");
        }).when(handler).handle(any());

        dispatcher.dispatch(customerMessage(10));
        runAll();
        dispatcher.dispatch(customerMessage(11));

        assertThat(queued).as("the conversation is free again").hasSize(1);
    }

    @Test
    void offModeSchedulesNothing() {
        when(handler.enabled()).thenReturn(false);

        dispatcher.dispatch(customerMessage(10));

        assertThat(queued).isEmpty();
        verify(handler, never()).handle(any());
    }

    @Test
    void underRealConcurrencyTheLatestMessageOfEveryConversationIsAnswered() throws Exception {
        int conversations = 50;
        int messagesEach = 20;
        java.util.Map<UUID, Long> lastHandled = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Set<UUID> concurrent = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.Set<UUID> overlapped = java.util.concurrent.ConcurrentHashMap.newKeySet();
        doAnswer(invocation -> {
            AdvisorTurnCommand command = invocation.getArgument(0);
            if (!concurrent.add(command.conversationId())) {
                overlapped.add(command.conversationId());
            }
            Thread.sleep(1);
            // Senders run on several threads, so an older message can be delivered after a newer one;
            // the handler skips such a stale turn (not the latest message). What must hold is that the
            // latest message is always answered.
            lastHandled.merge(command.conversationId(), command.triggeringCustomerMessageId(), Math::max);
            concurrent.remove(command.conversationId());
            return null;
        }).when(handler).handle(any());
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.ExecutorService senders = java.util.concurrent.Executors.newFixedThreadPool(8);
        AdvisorTurnDispatcher real = new AdvisorTurnDispatcher(handler, workers::execute);
        List<UUID> ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(conversations).toList();

        for (int m = 1; m <= messagesEach; m++) {
            long messageId = m;
            for (UUID id : ids) {
                senders.execute(() -> real.dispatch(ConversationEvent.messagePosted(
                        new MessageRecord(messageId, id, MessageSender.CUSTOMER, "m", Instant.now()),
                        ConversationStatus.ACTIVE)));
            }
        }
        senders.shutdown();
        assertThat(senders.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline
                && !ids.stream().allMatch(id -> Long.valueOf(messagesEach).equals(lastHandled.get(id)))) {
            Thread.sleep(10);
        }
        workers.shutdownNow();

        assertThat(ids).allSatisfy(id -> assertThat(lastHandled.get(id)).isEqualTo((long) messagesEach));
        assertThat(overlapped).as("never two turns in flight for one conversation").isEmpty();
    }

    private void runAll() {
        while (!queued.isEmpty()) {
            queued.poll().run();
        }
    }

    private static ConversationEvent customerMessage(long id) {
        return ConversationEvent.messagePosted(
                new MessageRecord(id, CONVERSATION, MessageSender.CUSTOMER, "message " + id, Instant.now()),
                ConversationStatus.ACTIVE);
    }
}
