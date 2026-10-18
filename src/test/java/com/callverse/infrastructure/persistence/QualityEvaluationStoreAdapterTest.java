package com.callverse.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.callverse.core.application.interfaces.QualityAgent.QualityFlags;
import com.callverse.core.application.interfaces.QualityAgent.QualityResult;
import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.entities.QualityEvaluation;
import com.callverse.core.domain.enums.EvaluatorType;
import com.callverse.infrastructure.persistence.repositories.ConversationRepository;
import com.callverse.infrastructure.persistence.repositories.QualityEvaluationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
class QualityEvaluationStoreAdapterTest {

    private static final UUID CONVERSATION_ID = UUID.fromString("182fe212-078b-4cf1-a8a7-7a161df3988b");

    @Mock
    private ConversationRepository conversations;

    @Mock
    private QualityEvaluationRepository evaluations;

    private QualityEvaluationStoreAdapter adapter;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        adapter = new QualityEvaluationStoreAdapter(conversations, evaluations, new ObjectMapper());
        conversation = new Conversation();
        when(conversations.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
    }

    @Test
    void mapsEvaluationToExistingDatabaseColumnsAndCriterionCodes() {
        adapter.save(new QualityResult(
                CONVERSATION_ID,
                new BigDecimal("8.4"),
                Map.of(
                        "relevance", 9,
                        "accuracy", 8,
                        "compliance", 8,
                        "communication", 9,
                        "empathy", 8,
                        "resolution", 8),
                "Clear and relevant response.",
                new QualityFlags(1, 0, List.of("verification missing"), List.of("article/chunk"))));

        ArgumentCaptor<QualityEvaluation> saved = ArgumentCaptor.forClass(QualityEvaluation.class);
        verify(evaluations).save(saved.capture());
        QualityEvaluation evaluation = saved.getValue();
        assertThat(evaluation.getConversation()).isSameAs(conversation);
        assertThat(evaluation.getEvaluator()).isEqualTo(EvaluatorType.AI);
        assertThat(evaluation.getGlobalScore()).isEqualByComparingTo("8.40");
        assertThat(evaluation.getScores().get("RELEVANCE").asInt()).isEqualTo(9);
        assertThat(evaluation.getScores().get("RESOLUTION").asInt()).isEqualTo(8);
        assertThat(evaluation.getFlags().get("unsourced_claims").asInt()).isEqualTo(1);
        assertThat(evaluation.getFlags().get("procedure_violations").get(0).asText())
                .isEqualTo("verification missing");
        assertThat(evaluation.getExplanation()).isEqualTo("Clear and relevant response.");
    }
}
