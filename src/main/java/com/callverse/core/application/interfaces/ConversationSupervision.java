package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.Intent;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The supervisor's view over every live conversation: find them by client, advisor, status, skill or
 * date, newest first, a page at a time. Opening one then uses the ordinary detail, transcript and live
 * topic, which a supervisor may already read.
 *
 * <p>Live conversations only ({@code run_id IS NULL}, rule C6): a simulation run is not floor activity.
 */
public interface ConversationSupervision {

    ConversationPage search(ConversationSearch search, int page, int size);

    /**
     * Every field is optional (null or empty = any).
     *
     * @param query matched against the customer's first name, last name and reference,
     *     case-insensitively; wildcards typed by the user are literals
     * @param from queued at or after this instant
     * @param to queued before this instant
     */
    record ConversationSearch(
            Set<ConversationStatus> statuses,
            String skill,
            UUID customerId,
            UUID advisorId,
            String query,
            Instant from,
            Instant to) {

        public ConversationSearch {
            statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        }
    }

    /**
     * One row of the supervisor's list. Unlike the customer- and advisor-facing view, it carries the
     * operating figures (wait, handle time, SLA outcome): the supervisor is who they exist for.
     *
     * @param advisorId null while the conversation waits in its queue
     * @param lastMessageAt null when nothing has been written yet
     * @param pendingEscalation whether an escalation is waiting for a supervisor
     */
    record ConversationSummary(
            UUID id,
            ConversationStatus status,
            String skill,
            Intent intent,
            String channel,
            Instant queuedAt,
            Instant assignedAt,
            Instant endedAt,
            UUID customerId,
            String customerName,
            String customerReference,
            UUID advisorId,
            String advisorName,
            Integer waitSeconds,
            Integer handleSeconds,
            Boolean slaMet,
            long messageCount,
            Instant lastMessageAt,
            boolean pendingEscalation) {}

    record ConversationPage(List<ConversationSummary> content, int page, int size, long totalElements, int totalPages) {

        public ConversationPage {
            content = List.copyOf(content);
        }
    }
}
