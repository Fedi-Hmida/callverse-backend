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
import com.callverse.core.domain.enums.Channel;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.CustomerSegment;
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
    private final AdvisorTurnCommandHandler handler = new AdvisorTurnCommandHandler(
            agent, conversations, messages, customers, events, Clock.fixed(NOW, ZoneOffset.UTC));

    private ConversationRecord conversation;
    private MessageRecord customerMessage;

    @BeforeEach
    void setUp() {
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
                3L, CONVERSATION_ID, MessageSender.CUSTOMER, "Je ne reconnais pas ce paiement.", NOW.minusSeconds(1));
        when(conversations.find(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(messages.recent(CONVERSATION_ID, 200)).thenReturn(List.of(
                new MessageRecord(1L, CONVERSATION_ID, MessageSender.SYSTEM, "Ouverture", NOW.minusSeconds(30)),
                new MessageRecord(2L, CONVERSATION_ID, MessageSender.ADVISOR, "Bonjour.", NOW.minusSeconds(10)),
                customerMessage));
        when(customers.findProfile(CUSTOMER_ID)).thenReturn(Optional.of(new CustomerProfile(
                CUSTOMER_ID, "CV-TEST", "Test", "Customer", "NORTH", CustomerSegment.AFFLUENT, 24, List.of())));
        when(agent.respond(any())).thenReturn(new AdvisorResponse(
                "Je vérifie ce paiement.", "FRAUD", 0.9, List.of(), List.of(), "NONE", Map.of(), 12));
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
    void skipsQueuedAndEscalatedConversations() {
        for (ConversationStatus status : List.of(ConversationStatus.QUEUED, ConversationStatus.ESCALATED)) {
            when(conversations.find(CONVERSATION_ID)).thenReturn(Optional.of(withStatus(status)));

            handler.handle(new AdvisorTurnCommand(CONVERSATION_ID, customerMessage.id()));
        }

        verify(agent, never()).respond(any());
        verify(messages, never()).postAgentTurn(any(), anyLong(), any(), any(), any(), any());
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
