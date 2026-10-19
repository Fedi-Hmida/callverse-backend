package com.callverse.core.application.interfaces;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Verifiable decision facts for staff, never the model's private internal reasoning. */
public interface ConversationAiExplanations {
    Optional<Explanation> find(UUID conversationId, long messageId);

    record Explanation(long messageId, boolean decisionRecorded, String intent, Double confidence,
            String action, String reason, Long latencyMs, List<SourceReference> sources, List<ToolCheck> checks) {}
    record SourceReference(UUID articleId, String title, Double score) {}
    record ToolCheck(String tool, boolean ok) {}
}
