package com.callverse.core.application.features.conversation.queries;

import com.callverse.core.domain.enums.ConversationStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A supervisor's search over live conversations. Every filter is optional.
 *
 * @param query matched against the customer's name or reference, at most 100 characters
 * @param from queued at or after; {@code to} queued before
 * @param page zero-based; {@code size} 1 to 100
 */
public record SearchConversationsQuery(
        Set<ConversationStatus> statuses,
        String skill,
        UUID customerId,
        UUID advisorId,
        String query,
        Instant from,
        Instant to,
        int page,
        int size) {}
