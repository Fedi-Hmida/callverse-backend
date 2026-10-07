package com.callverse.core.application.features.conversation.queries;

import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import java.util.UUID;

/** @param limit how many of the most recent messages, 1 to 200 */
public record GetTranscriptQuery(AuthenticatedPrincipal caller, UUID conversationId, int limit) {}
