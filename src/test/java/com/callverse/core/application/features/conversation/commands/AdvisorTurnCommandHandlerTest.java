package com.callverse.core.application.features.conversation.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.callverse.core.application.features.conversation.ConversationEvents;
import com.callverse.core.application.interfaces.AdvisorAgent;
import com.callverse.core.application.interfaces.AdvisorAgent.AdvisorResponse;
import com.callverse.core.application.interfaces.AdvisorAgent.CustomerContext;
import com.callverse.core.application.interfaces.AdvisorAgent.HistoryMessage;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import com.callverse.core.application.interfaces.ConversationMessages;
import com.callverse.core.application.interfaces.ConversationMessages.MessageRecord;
import com.callverse.core.application.interfaces.ConversationMessages.PostedMessage;
import com.callverse.core.application.interfaces.CustomerProfile;
import com.callverse.core.application.interfaces.CustomerRecords;
import com.callverse.core.domain.enums.AiAdvisorMode;
import com.callverse.core.domain.enums.Channel;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.EscalationRaisedBy;
import com.callverse.core.domain.enums.MessageSender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdvisorTurnCommandHandlerTest {

    private static final UUID CONVERSATION_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-17T08:00:00Z");

    private final AdvisorAgent agent = mock(AdvisorAgent.class);
    private final ConversationLifecycle conversations = mock(ConversationLifecycle.class);
    private final ConversationMessages messages = mock(ConversationMessages.class);
    private final CustomerRecords customers = mock(CustomerRecords.class);
    private final ConversationEvents events = mock(ConversationEvents.class);
    private final EscalateConversationCommandHandler escalation = mock(EscalateConversationCommandHandler.class);
    private final AdvisorTurnCommandHandler handler = handler(AiAdvisorMode.AUTONOMOUS);

    private ConversationRecord conversation;
    private MessageRecord customerMessage;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.doCallRealMethod().when(messages).postExplainedAgentTurn(any(), anyLong(), any(), any());
        conversation = new ConversationRecord(
                CONVERSATION_ID,
                CUSTOMER_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CARD",
                null,
                Channel.CHAT,
                ConversationStatus.ACTIVE,
                NOW.minusSeconds(30),
                NOW.minusSeconds(20),
                null);
        customerMessage = new MessageRecord(
                3L, CONVERSATION_ID, MessageSender.CUSTOMER, "Pouvez-vous vérifier ce paiement ?", NOW.minusSeconds(1));
        when(conversations.find(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(messages.recent(CONVERSATION_ID, 200)).thenReturn(List.of(
                new MessageRecord(1L, CONVERSATION_ID, MessageSender.SYSTEM, "Ouverture", NOW.minusSeconds(30)),
                new MessageRecord(2L, CONVERSATION_ID, MessageSender.ADVISOR, "Bonjour.", NOW.minusSeconds(10)),
                customerMessage));
        when(customers.findProfile(CUSTOMER_ID)).thenReturn(Optional.of(new CustomerProfile(
                CUSTOMER_ID, "CV-TEST", "Test", "Customer", "NORTH", CustomerSegment.AFFLUENT, 24, List.of())));
        when(agent.respond(any())).thenReturn(new AdvisorResponse(
                "Je vérifie ce paiement.", "CARD", 0.9, List.of(), List.of(), "NONE", Map.of(), 12));
    }

    @Test
    void requestsAndPersistsOneAdvisorTurnWithFilteredBoundedContext() {
        MessageRecord answer =
                new MessageRecord(4L, CONVERSATION_ID, MessageSender.ADVISOR, "Je vérifie ce paiement.", NOW);
        PostedMessage posted = new PostedMessage(answer, conversation, false);
        when(messages.postAgentTurn(
                        CONVERSATION_ID, customerMessage.id(), "Je vérifie ce paiement.", List.of(), List.of(), NOW))
                .thenReturn(Optional.of(posted));

        handler.handle(new AdvisorTurnCommand(CONVERSATION_ID, customerMessage.id()));

        ArgumentCaptor<AdvisorAgent.AdvisorRequest> request =
                ArgumentCaptor.forClass(AdvisorAgent.AdvisorRequest.class);
        verify(agent).respond(request.capture());
        assertThat(request.getValue().conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(request.getValue().customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(request.getValue().message()).isEqualTo(customerMessage.content());
        assertThat(request.getValue().history())
                .containsExactly(new HistoryMessage(MessageSender.ADVISOR, "Bonjour.", NOW.minusSeconds(10)));
        assertThat(request.getValue().context()).isEqualTo(new CustomerContext(24, CustomerSegment.AFFLUENT));
        verify(messages).postAgentTurn(
                CONVERSATION_ID, customerMessage.id(), "Je vérifie ce paiement.", List.of(), List.of(), NOW);
        verify(events).messagePosted(posted);
    }

    @Test
    void skipsTerminalConversations() {
        for (ConversationStatus status : List.of(ConversationStatus.RESOLVED, ConversationStatus.ABANDONED)) {
            when(conversations.find(CONVERSATION_ID)).thenReturn(Optional.of(withStatus(status)));

            handler.handle(new AdvisorTurnCommand(CONVERSATION_ID, customerMessage.id()));
        }

        verify(agent, never()).respond(any());
        verify(messages, never()).postAgentTurn(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void offModeNeverCallsTheAgent() {
        AdvisorTurnCommandHandler off = handler(AiAdvisorMode.OFF);

        off.handle(new AdvisorTurnCommand(CONVERSATION_ID, customerMessage.id()));

        assertThat(off.enabled()).isFalse();
        assertThat(handler.enabled()).isTrue();
        verify(agent, never()).respond(any());
        verify(messages, never()).postAgentTurn(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void staysSilentAfterHumanTakeover() {
        when(messages.humanAdvisorHasReplied(CONVERSATION_ID)).thenReturn(true);
        handler.handle(new AdvisorTurnCommand(CONVERSATION_ID, customerMessage.id()));
        verify(agent, never()).respond(any());
        verify(messages, never()).postAgentTurn(any(), anyLong(), any(), any(), any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ConversationStatus.class, names = {"QUEUED", "ESCALATED"})
    void respondsBeforeAssignmentAndDuringEscalation(ConversationStatus status) {
        when(conversations.find(CONVERSATION_ID)).thenReturn(Optional.of(withStatus(status)));
        handler.handle(new AdvisorTurnCommand(CONVERSATION_ID, customerMessage.id()));
        verify(agent).respond(any());
        verify(messages).postAgentTurn(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void personalIdentifiersAreMaskedBeforeTheAiSeesThem() {
        MessageRecord withIban = new MessageRecord(3L, CONVERSATION_ID, MessageSender.CUSTOMER,
                "Débitez FR76 3000 6000 0112 3456 7890 189, ma carte 4111 1111 1111 1111", NOW.minusSeconds(1));
        when(messages.recent(CONVERSATION_ID, 200)).thenReturn(List.of(
                new MessageRecord(2L, CONVERSATION_ID, MessageSender.CUSTOMER,
                        "Je suis joignable au 06 12 34 56 78", NOW.minusSeconds(10)),
                withIban));
        when(messages.postAgentTurn(any(), anyLong(), any(), any(), any(), any())).thenReturn(Optional.empty());

        handler.handle(new AdvisorTurnCommand(CONVERSATION_ID, withIban.id()));

        ArgumentCaptor<AdvisorAgent.AdvisorRequest> request =
                ArgumentCaptor.forClass(AdvisorAgent.AdvisorRequest.class);
        verify(agent).respond(request.capture());
        assertThat(request.getValue().message())
                .isEqualTo("Débitez FR76 **** **** 0189, ma carte **** **** **** 1111");
        assertThat(request.getValue().history()).extracting(HistoryMessage::content)
                .containsExactly("Je suis joignable au [téléphone masqué]");
    }

    @Test
    void anEscalateActionRaisesARealEscalationAfterTheReplyIsPosted() {
        PostedMessage posted = agentAnswers("ESCALATE", Map.of("reason", "Contestation d'un paiement"));

        handler.handle(new AdvisorTurnCommand(CONVERSATION_ID, customerMessage.id()));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(messages, escalation);
        order.verify(messages).postAgentTurn(any(), anyLong(), any(), any(), any(), any());
        order.verify(escalation).handle(new EscalateConversationCommand(
                CONVERSATION_ID, "Contestation d'un paiement", EscalationRaisedBy.AI, null));
        verify(events).messagePosted(posted);
    }

    @Test
    void anEscalateActionWithoutReasonUsesADefaultAndALongReasonIsCut() {
        agentAnswers("ESCALATE", Map.of());
        handler.handle(new AdvisorTurnCommand(CONVERSATION_ID, customerMessage.id()));
        verify(escalation).handle(new EscalateConversationCommand(
                CONVERSATION_ID, "Escalade demandée par l'assistant IA", EscalationRaisedBy.AI, null));

        agentAnswers("ESCALATE", Map.of("reason", "x".repeat(400)));
        handler.handle(new AdvisorTurnCommand(CONVERSATION_ID, customerMessage.id()));
        verify(escalation).handle(new EscalateConversationCommand(
                CONVERSATION_ID, "x".repeat(255), EscalationRaisedBy.AI, null));
    }

    @Test
    void otherActionsAndDiscardedRepliesNeverEscalate() {
        agentAnswers("NONE", Map.of());
        handler.handle(new AdvisorTurnCommand(CONVERSATION_ID, customerMessage.id()));

        when(agent.respond(any())).thenReturn(new AdvisorResponse(
                "Je transmets.", "FRAUD", 0.3, List.of(), List.of(), "ESCALATE", Map.of(), 12));
        when(messages.postAgentTurn(any(), anyLong(), any(), any(), any(), any())).thenReturn(Optional.empty());
        handler.handle(new AdvisorTurnCommand(CONVERSATION_ID, customerMessage.id()));

        verify(escalation, never()).handle(any());
    }

    @Test
    void aRefusedEscalationDoesNotUndoThePostedReply() {
        PostedMessage posted = agentAnswers("ESCALATE", Map.of("reason", "Fraude"));
        when(escalation.handle(any())).thenThrow(new com.callverse.core.domain.exceptions.InvalidStateTransitionException(
                ConversationStatus.RESOLVED, ConversationStatus.ESCALATED));

        handler.handle(new AdvisorTurnCommand(CONVERSATION_ID, customerMessage.id()));

        verify(events).messagePosted(posted);
    }

    @Test
    void providerFailurePostsAnHonestHandoverAndEscalates() {
        agentAnswers("NONE", Map.of());
        when(agent.respond(any())).thenThrow(new IllegalStateException("quota"));
        handler.handle(new AdvisorTurnCommand(CONVERSATION_ID, customerMessage.id()));
        verify(escalation).handle(new EscalateConversationCommand(CONVERSATION_ID,
                "Assistant IA indisponible : reprise humaine nécessaire", EscalationRaisedBy.AI, null));
        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(messages).postAgentTurn(any(), anyLong(), reply.capture(), any(), any(), any());
        assertThat(reply.getValue()).contains("temporairement indisponible");
    }

    @Test
    void aPersonalFraudReportEscalatesWithoutCallingTheProvider() {
        agentAnswers("NONE", Map.of());
        when(messages.recent(CONVERSATION_ID, 200)).thenReturn(List.of(new MessageRecord(
                3L, CONVERSATION_ID, MessageSender.CUSTOMER, "Je ne reconnais pas ce paiement.", NOW)));
        handler.handle(new AdvisorTurnCommand(CONVERSATION_ID, 3L));
        verify(agent, never()).respond(any());
        verify(escalation).handle(new EscalateConversationCommand(CONVERSATION_ID,
                "Suspicion de fraude : prise en charge humaine obligatoire", EscalationRaisedBy.AI, null));
    }

    @Test
    void fraudIntentCannotSuppressMandatoryEscalation() {
        agentAnswers("NONE", Map.of());
        when(agent.respond(any())).thenReturn(new AdvisorResponse(
                "Tout va bien.", "FRAUD", .99, List.of(), List.of(), "NONE", Map.of(), 12));
        handler.handle(new AdvisorTurnCommand(CONVERSATION_ID, customerMessage.id()));
        verify(escalation).handle(new EscalateConversationCommand(CONVERSATION_ID,
                "Suspicion de fraude : prise en charge humaine obligatoire", EscalationRaisedBy.AI, null));
    }

    @Test
    void failedProviderCannotEscalateAConversationThatMovedOn() {
        when(agent.respond(any())).thenThrow(new IllegalStateException("quota"));
        when(messages.postAgentTurn(any(), anyLong(), any(), any(), any(), any())).thenReturn(Optional.empty());
        handler.handle(new AdvisorTurnCommand(CONVERSATION_ID, customerMessage.id()));
        verify(escalation, never()).handle(any());
    }

    @Test
    void anUnexecutedCardBlockClaimIsNotExposedAsASuccess() {
        agentAnswers("NONE", Map.of());
        when(agent.respond(any())).thenReturn(new AdvisorResponse(
                "J'ai bloqué votre carte.", "CARD", .99, List.of(), List.of(), "NONE", Map.of(), 12));
        handler.handle(new AdvisorTurnCommand(CONVERSATION_ID, customerMessage.id()));
        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(messages).postAgentTurn(any(), anyLong(), reply.capture(), any(), any(), any());
        assertThat(reply.getValue()).contains("ne peux pas confirmer");
        verify(escalation).handle(new EscalateConversationCommand(CONVERSATION_ID,
                "Blocage de carte non exécuté par l'assistant", EscalationRaisedBy.AI, null));
    }

    private PostedMessage agentAnswers(String actionType, Map<String, Object> payload) {
        when(agent.respond(any())).thenReturn(new AdvisorResponse(
                "Je transmets votre demande à un superviseur.", "CARD", 0.4, List.of(), List.of(),
                actionType, payload, 12));
        MessageRecord answer = new MessageRecord(
                4L, CONVERSATION_ID, MessageSender.ADVISOR, "Je transmets votre demande à un superviseur.", NOW);
        PostedMessage posted = new PostedMessage(answer, withStatus(ConversationStatus.ACTIVE), true);
        when(messages.postAgentTurn(any(), anyLong(), any(), any(), any(), any())).thenReturn(Optional.of(posted));
        return posted;
    }

    private AdvisorTurnCommandHandler handler(AiAdvisorMode mode) {
        return new AdvisorTurnCommandHandler(
                agent, conversations, messages, customers, events, Clock.fixed(NOW, ZoneOffset.UTC), mode,
                escalation);
    }

    private ConversationRecord withStatus(ConversationStatus status) {
        return new ConversationRecord(
                conversation.id(),
                conversation.customerId(),
                conversation.customerUserId(),
                conversation.advisorId(),
                conversation.advisorUserId(),
                conversation.skill(),
                conversation.intent(),
                conversation.channel(),
                status,
                conversation.queuedAt(),
                conversation.assignedAt(),
                conversation.endedAt());
    }
}
