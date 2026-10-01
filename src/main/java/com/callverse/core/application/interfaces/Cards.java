package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.CardBlockReason;
import com.callverse.core.domain.enums.CardNetwork;
import com.callverse.core.domain.enums.CardStatus;
import com.callverse.core.domain.enums.CardType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Card access for the card-blocking use case. Read models only; never the PAN, which does not
 * exist anywhere in the system: {@code panLast4} is all there is.
 */
public interface Cards {

    Optional<CardRecord> find(UUID cardId);

    /**
     * Blocks the card if it is still ACTIVE when its row is locked; otherwise returns it unchanged.
     * The lock is what makes two simultaneous blocks resolve to one block with one reason and one
     * time, rather than the second silently overwriting the first.
     *
     * @return the card, and whether this call is the one that blocked it — only that call may
     *     announce the block
     */
    BlockOutcome blockIfActive(UUID cardId, CardBlockReason reason, Instant at);

    record BlockOutcome(CardRecord card, boolean blocked) {}

    record CardRecord(
            UUID id,
            UUID accountId,
            UUID customerId,
            String panLast4,
            CardNetwork network,
            CardType type,
            CardStatus status,
            LocalDate expiresOn,
            Instant blockedAt,
            CardBlockReason blockReason) {}
}
