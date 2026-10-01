package com.callverse.core.application.features.conversation.commands;

import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.application.interfaces.ConversationDirectory;
import com.callverse.core.application.interfaces.Escalations;
import com.callverse.core.application.interfaces.RealtimeEventPublisher;
import com.callverse.core.application.interfaces.SupervisionAlert;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.EscalationRaisedBy;
import com.callverse.core.domain.enums.EscalationStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A supervisor is alerted when an escalation is created, and not when a pending one is returned. */
class EscalateConversationCommandHandlerTest {

    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();

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

    private final List<SupervisionAlert> alerts = new ArrayList<>();
    private final RealtimeEventPublisher publisher = alerts::add;
    private final ConversationDirectory conversations =
            id -> Optional.of(new ConversationDirectory.ConversationRef(id, CUSTOMER, ConversationStatus.ACTIVE));

    @Test
    @DisplayName("a new escalation raises one ESCALATION_RAISED alert; returning the pending one raises nothing")
    void onlyANewEscalationAlerts() {
        FakeEscalations escalations = new FakeEscalations();
        EscalateConversationCommandHandler handler =
                new EscalateConversationCommandHandler(conversations, escalations, publisher);

        handler.handle(new EscalateConversationCommand(CONVERSATION, "fraude", EscalationRaisedBy.ADVISOR));
        handler.handle(new EscalateConversationCommand(CONVERSATION, "fraude", EscalationRaisedBy.ADVISOR));

        assertThat(alerts).hasSize(1);
        SupervisionAlert alert = alerts.get(0);
        assertThat(alert.type()).isEqualTo(SupervisionAlert.Type.ESCALATION_RAISED);
        assertThat(alert.conversationId()).isEqualTo(CONVERSATION);
        assertThat(alert.customerId()).isEqualTo(CUSTOMER);
        assertThat(alert.escalationId()).isEqualTo(escalations.pending.id());
        assertThat(alert.occurredAt()).isEqualTo(Instant.parse("2026-10-01T16:52:00Z"));
    }
}
