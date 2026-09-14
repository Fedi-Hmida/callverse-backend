package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.QualityEvaluation;
import com.callverse.core.domain.enums.EvaluatorType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Root of the quality study. Carries the query behind the inter-rater agreement measure.
 */
@Repository
public interface QualityEvaluationRepository extends JpaRepository<QualityEvaluation, UUID> {

    /** Uses idx_quality_conv. */
    List<QualityEvaluation> findByConversationId(UUID conversationId);

    /**
     * The pairs behind Cohen's kappa: conversations carrying both an AI and a human evaluation.
     * Expressed as a self-join because evaluator is what allows both to live in one table.
     */
    List<QualityEvaluation> findByConversationIdInAndEvaluator(
            List<UUID> conversationIds, EvaluatorType evaluator);
}
