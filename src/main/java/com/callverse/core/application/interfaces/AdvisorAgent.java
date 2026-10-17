package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.MessageSender;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The Customer Advisor agent, reached through an outbound port.
 *
 * <p>The request deliberately contains only information the backend already exposes for the
 * conversation: its customer id, tenure and segment, plus the transcript. It does not carry churn
 * risk, contract details or an invented open-ticket count.
 */
public interface AdvisorAgent {

    AdvisorResponse respond(AdvisorRequest request);

    record AdvisorRequest(
            UUID conversationId,
            UUID customerId,
            String message,
            List<HistoryMessage> history,
            CustomerContext context) {

        public AdvisorRequest {
            Objects.requireNonNull(conversationId, "conversationId must not be null");
            Objects.requireNonNull(customerId, "customerId must not be null");
            Objects.requireNonNull(message, "message must not be null");
            history = List.copyOf(Objects.requireNonNull(history, "history must not be null"));
            Objects.requireNonNull(context, "context must not be null");
        }
    }

    record HistoryMessage(MessageSender role, String content, Instant timestamp) {

        public HistoryMessage {
            if (role != MessageSender.CUSTOMER && role != MessageSender.ADVISOR) {
                throw new IllegalArgumentException("Advisor history only supports CUSTOMER and ADVISOR roles");
            }
            Objects.requireNonNull(content, "content must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    record CustomerContext(int tenureMonths, CustomerSegment segment) {

        public CustomerContext {
            if (tenureMonths < 0) {
                throw new IllegalArgumentException("tenureMonths must not be negative");
            }
            Objects.requireNonNull(segment, "segment must not be null");
        }
    }

    record AdvisorResponse(
            String reply,
            String intent,
            double confidence,
            List<ToolCall> toolCalls,
            List<Source> sources,
            String actionType,
            Map<String, Object> actionPayload,
            long latencyMs) {

        public AdvisorResponse {
            toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls must not be null"));
            sources = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
            actionPayload = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(actionPayload, "actionPayload must not be null")));
        }
    }

    record ToolCall(String tool, Map<String, Object> args, String resultSummary, boolean ok) {

        public ToolCall {
            Objects.requireNonNull(tool, "tool must not be null");
            args = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(args, "args must not be null")));
            Objects.requireNonNull(resultSummary, "resultSummary must not be null");
        }
    }

    record Source(UUID knowledgeBaseArticleId, double score) {

        public Source {
            Objects.requireNonNull(knowledgeBaseArticleId, "knowledgeBaseArticleId must not be null");
        }
    }
}
