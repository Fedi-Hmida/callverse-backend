package com.callverse.core.application.features.conversation.commands;

import com.callverse.core.domain.enums.EscalationRaisedBy;
import java.util.UUID;

/** A request to escalate a conversation. {@code raisedBy} is decided by the route, never by the request body. */
public record EscalateConversationCommand(UUID conversationId, String reason, EscalationRaisedBy raisedBy) {}
