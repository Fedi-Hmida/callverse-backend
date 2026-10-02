package com.callverse.core.application.features.conversation.queries;

import com.callverse.core.application.interfaces.AuthenticatedPrincipal;

public record GetQueuesQuery(AuthenticatedPrincipal caller) {}
