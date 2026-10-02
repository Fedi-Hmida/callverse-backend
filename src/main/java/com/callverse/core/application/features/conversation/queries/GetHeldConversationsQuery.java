package com.callverse.core.application.features.conversation.queries;

import com.callverse.core.application.interfaces.AuthenticatedPrincipal;

/** The caller's own conversations: an advisor's personal queue. */
public record GetHeldConversationsQuery(AuthenticatedPrincipal caller) {}
