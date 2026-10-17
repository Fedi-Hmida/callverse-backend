package com.callverse.core.application.features.quality.commands;

import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationMessages;
import com.callverse.core.application.interfaces.ConversationMessages.MessageRecord;
import com.callverse.core.application.interfaces.QualityAgent;
import com.callverse.core.application.interfaces.QualityAgent.QualityMessage;
import com.callverse.core.application.interfaces.QualityAgent.QualityRequest;
import com.callverse.core.application.interfaces.QualityAgent.QualityResult;
import com.callverse.core.application.interfaces.QualityEvaluationStore;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.MessageSender;
import com.callverse.core.domain.services.PersonalDataRedactor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Evaluates and persists an eligible resolved conversation. */
public class QualityEvaluationCommandHandler {

    private static final int TRANSCRIPT_LIMIT = 500;
    private static final Set<String> CRITERIA = Set.of(
            "relevance", "accuracy", "compliance", "communication", "empathy", "resolution");

    private final QualityAgent agent;
    private final ConversationLifecycle conversations;
    private final ConversationMessages messages;
    private final QualityEvaluationStore evaluations;

    public QualityEvaluationCommandHandler(
            QualityAgent agent,
            ConversationLifecycle conversations,
            ConversationMessages messages,
            QualityEvaluationStore evaluations) {
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
        this.conversations = Objects.requireNonNull(conversations, "conversations must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.evaluations = Objects.requireNonNull(evaluations, "evaluations must not be null");
    }

    /**
     * @return true when a new AI evaluation was persisted; false when the transcript was not eligible
     */
    public boolean handle(UUID conversationId) {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        var conversation = conversations.find(conversationId).orElse(null);
        if (conversation == null || conversation.status() != ConversationStatus.RESOLVED) {
            return false;
        }
        if (evaluations.hasAiEvaluation(conversationId)) {
            return false;
        }

        List<QualityMessage> transcript = messages.recent(conversationId, TRANSCRIPT_LIMIT).stream()
                .filter(message -> message.sender() == MessageSender.CUSTOMER
                        || message.sender() == MessageSender.ADVISOR)
                .map(QualityEvaluationCommandHandler::toQualityMessage)
                .toList();
        if (transcript.size() < 2 || transcript.stream().noneMatch(m -> m.sender() == MessageSender.ADVISOR)) {
            return false;
        }

        QualityResult result = agent.evaluate(new QualityRequest(conversationId, transcript, true));
        validateResult(conversationId, result);
        evaluations.save(result);
        return true;
    }

    /** The judge is an external model: identifiers are masked, the stored transcript is not. */
    private static QualityMessage toQualityMessage(MessageRecord message) {
        return new QualityMessage(message.sender(), PersonalDataRedactor.redact(message.content()));
    }

    private static void validateResult(UUID conversationId, QualityResult result) {
        if (!conversationId.equals(result.conversationId())
                || result.globalScore().compareTo(BigDecimal.ZERO) < 0
                || result.globalScore().compareTo(BigDecimal.TEN) > 0
                || !result.scores().keySet().equals(CRITERIA)
                || result.scores().values().stream().anyMatch(score -> score == null || score < 0 || score > 10)) {
            throw new IllegalStateException("Quality service returned an invalid evaluation.");
        }
    }
}
