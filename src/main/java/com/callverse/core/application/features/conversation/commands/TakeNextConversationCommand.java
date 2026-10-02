package com.callverse.core.application.features.conversation.commands;

import com.callverse.core.application.interfaces.AuthenticatedPrincipal;

/** An advisor asking for the next conversation waiting in one of their skill queues. */
public record TakeNextConversationCommand(AuthenticatedPrincipal caller, String skill) {}
