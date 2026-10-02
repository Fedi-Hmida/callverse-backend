package com.callverse.core.application.features.conversation.commands;

import com.callverse.core.domain.enums.EscalationRaisedBy;
import java.util.UUID;

/**
 * A request to escalate a conversation. {@code raisedBy} is decided by the route, never by the
 * request body.
 *
 * @param requestedByUserId the login asking; for an ADVISOR escalation it must be the conversation's
 *     assigned advisor
 */
public record EscalateConversationCommand(
        UUID conversationId, String reason, EscalationRaisedBy raisedBy, UUID requestedByUserId) {}
