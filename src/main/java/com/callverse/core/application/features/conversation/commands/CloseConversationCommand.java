package com.callverse.core.application.features.conversation.commands;

import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.domain.enums.ConversationStatus;
import java.util.UUID;

/**
 * Ends a conversation.
 *
 * @param target {@code RESOLVED} or {@code ABANDONED}; the route decides which, never the body
 */
public record CloseConversationCommand(AuthenticatedPrincipal caller, UUID conversationId, ConversationStatus target) {}
