package com.callverse.core.application.features.conversation.commands;

import com.callverse.core.domain.enums.Intent;
import java.util.UUID;

/**
 * A customer contact arriving at the bank: the switchboard, a branch or the chat entry point
 * registers it on the customer's behalf.
 *
 * @param skill the skill code of the queue it enters
 * @param intent what the contact is about, if known; null when not yet classified
 */
public record OpenConversationCommand(UUID customerId, String skill, Intent intent) {}
