package com.callverse.core.application.interfaces;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.callverse.core.domain.enums.ConversationStatus;

/**
 * A bounded directory with ownership enforced by the authenticated caller. A customer never sees the
 * conversations they removed; staff still do, flagged {@code removedByCustomer}.
 */
public interface AccessibleConversationDirectory {
    Page search(AuthenticatedPrincipal caller, String query, ConversationStatus status, int page);

    /**
     * @param counterpartName who is on the other side for this caller, to head the row: the advisor
     *     for a customer (null while waiting for one), the customer for staff
     * @param subject what the request is about, in French (intent, else the skill's label)
     */
    record Item(UUID id, String customerName, String advisorName, String skill, String intent,
                ConversationStatus status, Instant queuedAt, String preview,
                String counterpartName, String subject, boolean removedByCustomer) {}
    record Page(List<Item> content, int page, int size, boolean hasNext) {}
}
