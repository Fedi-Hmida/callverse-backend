package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.QualityAgent.QualityFlags;
import com.callverse.core.application.interfaces.QualityAgent.QualityResult;
import com.callverse.core.application.interfaces.QualityEvaluationStore;
import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.entities.QualityEvaluation;
import com.callverse.core.domain.enums.EvaluatorType;
import com.callverse.infrastructure.persistence.repositories.ConversationRepository;
import com.callverse.infrastructure.persistence.repositories.QualityEvaluationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Persists AI Quality evaluations in the existing schema. */
@Component
@RequiredArgsConstructor
class QualityEvaluationStoreAdapter implements QualityEvaluationStore {

    private final ConversationRepository conversations;
    private final QualityEvaluationRepository evaluations;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<UUID> ungradedResolved(Instant endedAfter, Instant endedBefore, int limit) {
        return conversations.findUngradedResolved(endedAfter, endedBefore, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasAiEvaluation(UUID conversationId) {
        return evaluations.existsByConversationIdAndEvaluator(conversationId, EvaluatorType.AI);
    }

    @Override
    @Transactional
    public void save(QualityResult result) {
        Conversation conversation = conversations.findById(result.conversationId()).orElseThrow(
                () -> new IllegalStateException("Resolved conversation no longer exists."));

        QualityEvaluation evaluation = new QualityEvaluation();
        evaluation.setConversation(conversation);
        evaluation.setGlobalScore(result.globalScore().setScale(2, RoundingMode.HALF_UP));
        evaluation.setScores(objectMapper.valueToTree(toSchemaScores(result.scores())));
        evaluation.setExplanation(result.explanation());
        evaluation.setFlags(objectMapper.valueToTree(toSchemaFlags(result.flags())));
        evaluation.setEvaluator(EvaluatorType.AI);
        evaluations.save(evaluation);
    }

    private static Map<String, Integer> toSchemaScores(Map<String, Integer> scores) {
        return scores.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().toUpperCase(Locale.ROOT), Map.Entry::getValue));
    }

    private static Map<String, Object> toSchemaFlags(QualityFlags flags) {
        return Map.of(
                "unsourced_claims", flags.unsourcedClaims(),
                "unverifiable_claims", flags.unverifiableClaims(),
                "procedure_violations", flags.procedureViolations(),
                "missing_source_keys", flags.missingSourceKeys());
    }
}
