package com.callverse.core.application.features.conversation.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.callverse.core.application.RecordingPublisher;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.features.conversation.ConversationEvents;
import com.callverse.core.application.features.conversation.InMemoryConversations;
import com.callverse.core.application.interfaces.ConversationDirectory;
import com.callverse.core.application.interfaces.ConversationEvent;
import com.callverse.core.application.interfaces.Escalations;
import com.callverse.core.application.interfaces.SupervisionAlert;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.EscalationRaisedBy;
import com.callverse.core.domain.enums.EscalationStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Escalation: only the conversation's own advisor raises it; a new one alerts the supervisors and
 * announces the status change, a returned pending one announces nothing.
 */
class EscalateConversationCommandHandlerTest {

    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID KARIM = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T16:52:00Z"), ZoneOffset.UTC);

    private static final class FakeEscalations implements Escalations {
        EscalationRecord pending;

        @Override
        public Optional<EscalationRecord> findPending(UUID conversationId) {
            return Optional.ofNullable(pending);
        }

        @Override
        public EscalationOutcome raiseUnlessPending(UUID conversationId, String reason, EscalationRaisedBy raisedBy) {
            if (pending != null) {
                return new EscalationOutcome(pending, false);
            }
            pending = new EscalationRecord(UUID.randomUUID(), conversationId, reason, raisedBy,
                    EscalationStatus.PENDING, Instant.parse("2026-10-01T16:52:00Z"));
            return new EscalationOutcome(pending, true);
        }
    }

    private final RecordingPublisher publisher = new RecordingPublisher();
    private final ConversationEvents events =
            new ConversationEvents(publisher, new InMemoryConversations(), CLOCK, ZoneId.of("Europe/Paris"));
    private final ConversationDirectory conversations = id -> Optional.of(
            new ConversationDirectory.ConversationRef(id, CUSTOMER, ConversationStatus.ACTIVE, KARIM));

    /** Like the adapter: once an escalation is raised, the conversation reads as ESCALATED. */
    private static ConversationDirectory following(FakeEscalations escalations) {
        return id -> Optional.of(new ConversationDirectory.ConversationRef(id, CUSTOMER,
                escalations.pending == null ? ConversationStatus.ACTIVE : ConversationStatus.ESCALATED, KARIM));
    }

    @Test
    @DisplayName("a new escalation raises one ESCALATION_RAISED alert and one status change; a repeat raises nothing")
    void onlyANewEscalationAlerts() {
        FakeEscalations escalations = new FakeEscalations();
        EscalateConversationCommandHandler handler =
                new EscalateConversationCommandHandler(following(escalations), escalations, publisher, events);

        handler.handle(new EscalateConversationCommand(CONVERSATION, "fraude", EscalationRaisedBy.ADVISOR, KARIM));
        handler.handle(new EscalateConversationCommand(CONVERSATION, "fraude", EscalationRaisedBy.ADVISOR, KARIM));

        assertThat(publisher.alerts).hasSize(1);
        SupervisionAlert alert = publisher.alerts.get(0);
        assertThat(alert.type()).isEqualTo(SupervisionAlert.Type.ESCALATION_RAISED);
        assertThat(alert.conversationId()).isEqualTo(CONVERSATION);
        assertThat(alert.customerId()).isEqualTo(CUSTOMER);
        assertThat(alert.escalationId()).isEqualTo(escalations.pending.id());
        assertThat(alert.occurredAt()).isEqualTo(Instant.parse("2026-10-01T16:52:00Z"));
        assertThat(publisher.conversationEvents).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(ConversationEvent.Type.STATUS_CHANGED);
            assertThat(e.status()).isEqualTo(ConversationStatus.ESCALATED);
        });
    }

    @Test
    @DisplayName("a pending escalation on a conversation still ACTIVE (raised before escalating moved it) is completed: "
            + "a status change, no second alert")
    void legacyPendingEscalationHeals() {
        FakeEscalations escalations = new FakeEscalations();
        escalations.raiseUnlessPending(CONVERSATION, "old", EscalationRaisedBy.ADVISOR);
        EscalateConversationCommandHandler handler =
                new EscalateConversationCommandHandler(conversations, escalations, publisher, events);

        var outcome = handler.handle(new EscalateConversationCommand(CONVERSATION, "fraude", EscalationRaisedBy.ADVISOR, KARIM));

        assertThat(outcome.created()).isFalse();
        assertThat(publisher.alerts).isEmpty();
        assertThat(publisher.conversationEvents).singleElement()
                .satisfies(e -> assertThat(e.status()).isEqualTo(ConversationStatus.ESCALATED));
    }

    @Test
    @DisplayName("another advisor's conversation is answered 404, before any pending escalation is revealed")
    void onlyTheAssignedAdvisor() {
        FakeEscalations escalations = new FakeEscalations();
        escalations.raiseUnlessPending(CONVERSATION, "existing", EscalationRaisedBy.ADVISOR);
        EscalateConversationCommandHandler handler =
                new EscalateConversationCommandHandler(conversations, escalations, publisher, events);

        assertThatThrownBy(() -> handler.handle(new EscalateConversationCommand(
                        CONVERSATION, "fraude", EscalationRaisedBy.ADVISOR, UUID.randomUUID())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(publisher.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("an unassigned conversation belongs to no advisor: 404")
    void unassigned() {
        ConversationDirectory unassigned = id -> Optional.of(
                new ConversationDirectory.ConversationRef(id, CUSTOMER, ConversationStatus.ACTIVE, null));
        EscalateConversationCommandHandler handler =
                new EscalateConversationCommandHandler(unassigned, new FakeEscalations(), publisher, events);

        assertThatThrownBy(() -> handler.handle(new EscalateConversationCommand(
                        CONVERSATION, "fraude", EscalationRaisedBy.ADVISOR, KARIM)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
