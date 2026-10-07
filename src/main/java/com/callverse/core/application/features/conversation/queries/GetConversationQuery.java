package com.callverse.core.application.features.conversation.queries;

import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import java.util.UUID;

public record GetConversationQuery(AuthenticatedPrincipal caller, UUID conversationId) {}
