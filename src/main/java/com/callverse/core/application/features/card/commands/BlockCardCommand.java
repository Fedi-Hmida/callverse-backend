package com.callverse.core.application.features.card.commands;

import com.callverse.core.domain.enums.CardBlockReason;
import java.util.UUID;

/**
 * @param cardId the card to block
 * @param reason why: it is stored with the block and cannot be changed by a second request
 */
public record BlockCardCommand(UUID cardId, CardBlockReason reason) {}
