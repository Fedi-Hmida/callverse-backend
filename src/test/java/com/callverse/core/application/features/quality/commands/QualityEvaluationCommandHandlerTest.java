package com.callverse.core.application.features.quality.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.callverse.core.application.interfaces.CitedKnowledge;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import com.callverse.core.application.interfaces.ConversationMessages;
import com.callverse.core.application.interfaces.ConversationMessages.MessageRecord;
import com.callverse.core.application.interfaces.QualityAgent;
import com.callverse.core.application.interfaces.QualityAgent.QualityFlags;
import com.callverse.core.application.interfaces.QualityAgent.QualityMessage;
import com.callverse.core.application.interfaces.QualityAgent.QualityRequest;
import com.callverse.core.application.interfaces.QualityAgent.QualityResult;
import com.callverse.core.application.interfaces.QualityAgent.SourceDocument;
import com.callverse.core.application.interfaces.QualityEvaluationStore;
import com.callverse.core.domain.services.BankingProcedureRules;
import com.callverse.core.domain.enums.Channel;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.MessageSender;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QualityEvaluationCommandHandlerTest {

    private static final UUID CONVERSATION_ID = UUID.fromString("182fe212-078b-4cf1-a8a7-7a161df3988b");

    @Mock
    private QualityAgent agent;

    @Mock
    private ConversationLifecycle conversations;

    @Mock
    private ConversationMessages messages;

    @Mock
    private QualityEvaluationStore evaluations;

    @Mock
    private CitedKnowledge citedKnowledge;

    private QualityEvaluationCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new QualityEvaluationCommandHandler(agent, conversations, messages, evaluations, citedKnowledge);
    }

    @Test
    void theJudgeReceivesTheArticlesTheAiCitedAndTheBankRules() {
        when(conversations.find(CONVERSATION_ID)).thenReturn(Optional.of(resolvedConversation()));
        when(evaluations.hasAiEvaluation(CONVERSATION_ID)).thenReturn(false);
        when(messages.recent(CONVERSATION_ID, 500)).thenReturn(List.of(
                message(MessageSender.CUSTOMER, "Ma carte est perdue."),
                message(MessageSender.ADVISOR, "Je fais opposition tout de suite.")));
        SourceDocument opposition = new SourceDocument(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "article", "Opposition carte\nEn cas de perte...");
        when(citedKnowledge.citedInAiTurns(CONVERSATION_ID)).thenReturn(List.of(opposition));
        when(agent.evaluate(any(QualityRequest.class))).thenReturn(result());

        handler.handle(CONVERSATION_ID);

        ArgumentCaptor<QualityRequest> request = ArgumentCaptor.forClass(QualityRequest.class);
        verify(agent).evaluate(request.capture());
        assertThat(request.getValue().sourceDocuments()).containsExactly(opposition);
        assertThat(request.getValue().applicableRules())
                .isEqualTo(BankingProcedureRules.ALL)
                .anySatisfy(rule -> assertThat(rule).contains("identifié"));
    }

    @Test
    void evaluatesResolvedConversationUsingOnlyCustomerAndAdvisorTranscript() {
        when(conversations.find(CONVERSATION_ID)).thenReturn(Optional.of(resolvedConversation()));
        when(evaluations.hasAiEvaluation(CONVERSATION_ID)).thenReturn(false);
        when(messages.recent(CONVERSATION_ID, 500))
                .thenReturn(List.of(
                        message(MessageSender.SYSTEM, "Queue position updated."),
                        message(MessageSender.CUSTOMER, "My card was charged twice."),
                        message(MessageSender.ADVISOR, "I have opened a dispute for the duplicate charge.")));
        when(agent.evaluate(any(QualityRequest.class))).thenReturn(result());

        assertThat(handler.handle(CONVERSATION_ID)).isTrue();

        ArgumentCaptor<QualityRequest> request = ArgumentCaptor.forClass(QualityRequest.class);
        verify(agent).evaluate(request.capture());
        assertThat(request.getValue().resolved()).isTrue();
        assertThat(request.getValue().messages())
                .extracting(QualityMessage::sender)
                .containsExactly(MessageSender.CUSTOMER, MessageSender.ADVISOR);
        verify(evaluations).save(result());
    }

    @Test
    void theTranscriptIsMaskedBeforeTheJudgeSeesIt() {
        when(conversations.find(CONVERSATION_ID)).thenReturn(Optional.of(resolvedConversation()));
        when(evaluations.hasAiEvaluation(CONVERSATION_ID)).thenReturn(false);
        when(messages.recent(CONVERSATION_ID, 500)).thenReturn(List.of(
                message(MessageSender.CUSTOMER, "Remboursez sur FR76 3000 6000 0112 3456 7890 189"),
                message(MessageSender.ADVISOR, "Je vous écris à amina.haddad@example.fr")));
        when(agent.evaluate(any(QualityRequest.class))).thenReturn(result());

        handler.handle(CONVERSATION_ID);

        ArgumentCaptor<QualityRequest> request = ArgumentCaptor.forClass(QualityRequest.class);
        verify(agent).evaluate(request.capture());
        assertThat(request.getValue().messages()).extracting(QualityMessage::content).containsExactly(
                "Remboursez sur FR76 **** **** 0189", "Je vous écris à [e-mail masqué]");
    }

    @Test
    void skipsUnresolvedConversationAndDoesNotCallAgent() {
        ConversationRecord active = new ConversationRecord(
                CONVERSATION_ID,
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                null,
                "CARDS",
                null,
                Channel.CHAT,
                ConversationStatus.ACTIVE,
                Instant.EPOCH,
                Instant.EPOCH,
                null);
        when(conversations.find(CONVERSATION_ID)).thenReturn(Optional.of(active));

        assertThat(handler.handle(CONVERSATION_ID)).isFalse();

        verify(agent, never()).evaluate(any());
        verify(evaluations, never()).save(any());
    }

    @Test
    void skipsAConversationAlreadyEvaluatedByAi() {
        when(conversations.find(CONVERSATION_ID)).thenReturn(Optional.of(resolvedConversation()));
        when(evaluations.hasAiEvaluation(CONVERSATION_ID)).thenReturn(true);

        assertThat(handler.handle(CONVERSATION_ID)).isFalse();

        verify(agent, never()).evaluate(any());
        verify(messages, never()).recent(any(), any(Integer.class));
    }

    private static ConversationRecord resolvedConversation() {
        return new ConversationRecord(
                CONVERSATION_ID,
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                null,
                "CARDS",
                null,
                Channel.CHAT,
                ConversationStatus.RESOLVED,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH);
    }

    private static MessageRecord message(MessageSender sender, String content) {
        return new MessageRecord(1, CONVERSATION_ID, sender, content, Instant.EPOCH);
    }

    private static QualityResult result() {
        return new QualityResult(
                CONVERSATION_ID,
                new BigDecimal("8.40"),
                Map.of(
                        "relevance", 9,
                        "accuracy", 8,
                        "compliance", 8,
                        "communication", 9,
                        "empathy", 8,
                        "resolution", 8),
                "Clear and relevant response.",
                new QualityFlags(0, 1, List.of(), List.of()));
    }
}
