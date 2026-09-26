package com.callverse.core.application.features.conversation.commands;

import java.util.UUID;

/** The agent asks for a human. Deliberately has no "raised by": the AI tool always raises as AI. */
public record EscalateConversationCommand(UUID conversationId, String reason) {}
