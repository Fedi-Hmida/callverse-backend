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

    /** A knowledge-base passage the judge may cite as {@code documentId/chunkId}. */
    record SourceDocument(String documentId, String chunkId, String content) {

        public SourceDocument {
            Objects.requireNonNull(documentId, "documentId must not be null");
            Objects.requireNonNull(chunkId, "chunkId must not be null");
            Objects.requireNonNull(content, "content must not be null");
        }
    }

    /**
     * @param sourceDocuments the articles the AI cited, so claims can be checked against them
     * @param applicableRules the bank's procedure rules the conversation must follow
     */
    record QualityRequest(
            UUID conversationId,
            List<QualityMessage> messages,
            boolean resolved,
            List<SourceDocument> sourceDocuments,
            List<String> applicableRules) {

        public QualityRequest {
            Objects.requireNonNull(conversationId, "conversationId must not be null");
            messages = List.copyOf(messages);
            sourceDocuments = List.copyOf(sourceDocuments);
            applicableRules = List.copyOf(applicableRules);
        }

        /** A transcript-only request, with no grounding. */
        public QualityRequest(UUID conversationId, List<QualityMessage> messages, boolean resolved) {
            this(conversationId, messages, resolved, List.of(), List.of());
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
