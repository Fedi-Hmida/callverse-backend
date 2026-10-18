package com.callverse.core.application.features.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.callverse.core.application.RecordingPublisher;
import com.callverse.core.application.exceptions.ActionNotPermittedException;
import com.callverse.core.application.features.conversation.ConversationEvents;
import com.callverse.core.application.features.conversation.InMemoryConversations;
import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.application.interfaces.ConversationEvent;
import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import com.callverse.core.application.interfaces.ConversationMessages.MessageRecord;
import com.callverse.core.application.interfaces.CustomerConversationRemoval;
import com.callverse.core.application.interfaces.QueueEvent;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.MessageSender;
import com.callverse.core.domain.enums.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A customer removes a conversation from their own space. The bank keeps the record; the advisor and
 * the supervisor are told live, on channels they already listen to.
 */
class RemoveOwnConversationCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-18T20:00:00Z");

    private final InMemoryConversations store = new InMemoryConversations();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ConversationEvents events = new ConversationEvents(publisher, store, clock, ZoneId.of("Europe/Paris"));
    private final List<String> removals = new ArrayList<>();

    private RemoveOwnConversationCommandHandler handler(UserRole role, UUID userId, ConversationStatus before,
                                                       ConversationRecord after) {
        CustomerConversationRemoval port = (conversationId, customerUserId, notice, now) -> {
            removals.add(conversationId + "|" + customerUserId + "|" + notice + "|" + now);
            return new CustomerConversationRemoval.Removal(before, after,
                    new MessageRecord(99L, conversationId, MessageSender.SYSTEM, notice, now));
        };
        return new RemoveOwnConversationCommandHandler(
                () -> Optional.of(new AuthenticatedPrincipal(userId, "someone@test.local", role)), port, events, clock);
    }

    private ConversationRecord ended(ConversationRecord live, ConversationStatus status) {
        return new ConversationRecord(live.id(), live.customerId(), null, live.advisorId(), live.advisorUserId(),
                live.skill(), live.intent(), live.channel(), status, live.queuedAt(), live.assignedAt(), NOW);
    }

    @Test
    @DisplayName("removing a conversation in service ends it and tells the advisor three ways, in order")
    void removingAnAssignedConversationAnnouncesTheNoticeTheEndAndTheRemoval() {
        UUID customerUser = UUID.randomUUID();
        ConversationRecord live = store.put(ConversationStatus.ASSIGNED, "CARDS", customerUser, UUID.randomUUID());

        handler(UserRole.CUSTOMER, customerUser, ConversationStatus.ASSIGNED, ended(live, ConversationStatus.ABANDONED))
                .handle(live.id());

        assertThat(removals).containsExactly(live.id() + "|" + customerUser + "|"
                + RemoveOwnConversationCommandHandler.NOTICE + "|" + NOW);
        assertThat(publisher.conversationEvents).extracting(ConversationEvent::type)
                .containsExactly(ConversationEvent.Type.MESSAGE_POSTED, ConversationEvent.Type.STATUS_CHANGED);
        ConversationEvent notice = publisher.conversationEvents.get(0);
        assertThat(notice.sender()).isEqualTo(MessageSender.SYSTEM);
        assertThat(notice.content()).isEqualTo(RemoveOwnConversationCommandHandler.NOTICE);
        assertThat(publisher.conversationEvents.get(1).status()).isEqualTo(ConversationStatus.ABANDONED);
        assertThat(publisher.queueEvents).extracting(QueueEvent::type)
                .containsExactly(QueueEvent.Type.CONVERSATION_DELETED_BY_CUSTOMER);
        assertThat(publisher.queueEvents.get(0).skill()).isEqualTo("CARDS");
        assertThat(publisher.queueEvents.get(0).conversationId()).isEqualTo(live.id());
    }

    @Test
    @DisplayName("removing a waiting contact takes it out of the queue before announcing the removal")
    void removingAQueuedConversationLeavesTheQueue() {
        UUID customerUser = UUID.randomUUID();
        ConversationRecord queued = store.put(ConversationStatus.QUEUED, "ACCOUNTS", customerUser, null);

        handler(UserRole.CUSTOMER, customerUser, ConversationStatus.QUEUED, ended(queued, ConversationStatus.ABANDONED))
                .handle(queued.id());

        assertThat(publisher.queueEvents).extracting(QueueEvent::type).containsExactly(
                QueueEvent.Type.CONVERSATION_LEFT_QUEUE, QueueEvent.Type.CONVERSATION_DELETED_BY_CUSTOMER);
    }

    @Test
    @DisplayName("removing a conversation already closed changes no status, but still leaves the notice")
    void removingAClosedConversationOnlyHidesItAndLeavesTheNotice() {
        UUID customerUser = UUID.randomUUID();
        ConversationRecord resolved = store.put(ConversationStatus.RESOLVED, "CARDS", customerUser, UUID.randomUUID());

        handler(UserRole.CUSTOMER, customerUser, ConversationStatus.RESOLVED, ended(resolved, ConversationStatus.RESOLVED))
                .handle(resolved.id());

        assertThat(publisher.conversationEvents).extracting(ConversationEvent::type)
                .containsExactly(ConversationEvent.Type.MESSAGE_POSTED);
        assertThat(publisher.queueEvents).extracting(QueueEvent::type)
                .containsExactly(QueueEvent.Type.CONVERSATION_DELETED_BY_CUSTOMER);
    }

    @Test
    @DisplayName("only a customer removes from their own space: staff are refused before anything is touched")
    void staffCannotUseTheCustomerRemoval() {
        ConversationRecord live = store.put(ConversationStatus.ACTIVE, "CARDS", UUID.randomUUID(), UUID.randomUUID());
        for (UserRole role : List.of(UserRole.ADVISOR, UserRole.SUPERVISOR, UserRole.ADMIN)) {
            assertThatThrownBy(() -> handler(role, UUID.randomUUID(), ConversationStatus.ACTIVE, live).handle(live.id()))
                    .isInstanceOf(ActionNotPermittedException.class);
        }
        assertThat(removals).isEmpty();
        assertThat(publisher.isEmpty()).isTrue();
    }
}
