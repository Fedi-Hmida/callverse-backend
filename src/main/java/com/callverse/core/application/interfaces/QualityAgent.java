package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.MessageSender;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Port to the Quality Analyst, which scores a resolved conversation transcript. */
public interface QualityAgent {

    QualityResult evaluate(QualityRequest request);

    record QualityMessage(MessageSender sender, String content) {

        public QualityMessage {
            Objects.requireNonNull(sender, "sender must not be null");
            Objects.requireNonNull(content, "content must not be null");
        }
    }

    record QualityRequest(UUID conversationId, List<QualityMessage> messages, boolean resolved) {

        public QualityRequest {
            Objects.requireNonNull(conversationId, "conversationId must not be null");
            messages = List.copyOf(messages);
        }
    }

    record QualityResult(
            UUID conversationId,
            BigDecimal globalScore,
            Map<String, Integer> scores,
            String explanation,
            QualityFlags flags) {

        public QualityResult {
            Objects.requireNonNull(conversationId, "conversationId must not be null");
            Objects.requireNonNull(globalScore, "globalScore must not be null");
            scores = Map.copyOf(scores);
            Objects.requireNonNull(explanation, "explanation must not be null");
            Objects.requireNonNull(flags, "flags must not be null");
        }
    }

    record QualityFlags(
            int unsourcedClaims,
            int unverifiableClaims,
            List<String> procedureViolations,
            List<String> missingSourceKeys) {

        public QualityFlags {
            if (unsourcedClaims < 0 || unverifiableClaims < 0) {
                throw new IllegalArgumentException("claim counts must not be negative");
            }
            procedureViolations = List.copyOf(procedureViolations);
            missingSourceKeys = List.copyOf(missingSourceKeys);
        }
    }
}
