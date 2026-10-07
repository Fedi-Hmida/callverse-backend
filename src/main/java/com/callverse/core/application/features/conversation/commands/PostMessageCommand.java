package com.callverse.core.application.features.conversation.commands;

import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import java.util.UUID;

/** A message written into a conversation. Who it is from is decided by the caller, never by the request. */
public record PostMessageCommand(AuthenticatedPrincipal caller, UUID conversationId, String content) {}
