package com.callverse.core.application.interfaces;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.callverse.core.domain.enums.ConversationStatus;

/** A bounded directory with ownership enforced by the authenticated caller. */
public interface AccessibleConversationDirectory {
    Page search(AuthenticatedPrincipal caller, String query, ConversationStatus status, int page);
    record Item(UUID id, String customerName, String advisorName, String skill, String intent,
                ConversationStatus status, Instant queuedAt, String preview) {}
    record Page(List<Item> content, int page, int size, boolean hasNext) {}
}
