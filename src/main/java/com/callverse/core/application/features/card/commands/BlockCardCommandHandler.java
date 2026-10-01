package com.callverse.core.application.features.card.commands;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.Cards;
import com.callverse.core.application.interfaces.Cards.CardRecord;
import com.callverse.core.domain.enums.CardStatus;
import com.callverse.core.domain.exceptions.InvalidStateTransitionException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Blocks a payment card: the first thing an advisor does on a lost, stolen or suspected-fraud call.
 *
 * <p><strong>Three rules, in this order.</strong>
 *
 * <ol>
 *   <li><em>Idempotent.</em> A card that is already BLOCKED is returned as it is. The first block's
 *       reason and time are the record of the incident; a second request — a retry, or a colleague
 *       on the same case — must not rewrite them.
 *   <li><em>Only an ACTIVE card can be blocked.</em> An EXPIRED or CANCELLED card cannot be used
 *       anyway, and blocking it would invent a block that never protected anything: 409
 *       {@code INVALID_STATE_TRANSITION}.
 *   <li><em>The backend sets the time</em>, from the injected {@link Clock}. The caller cannot
 *       back-date a block.
 * </ol>
 */
public class BlockCardCommandHandler {

    private final Cards cards;
    private final Clock clock;

    public BlockCardCommandHandler(Cards cards, Clock clock) {
        this.cards = Objects.requireNonNull(cards, "cards must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public CardRecord handle(BlockCardCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.reason() == null) {
            throw new InvalidRequestException("reason is required");
        }
        CardRecord card = cards.find(command.cardId())
                .orElseThrow(() -> new ResourceNotFoundException("Card", command.cardId()));
        if (card.status() == CardStatus.BLOCKED) {
            return card;
        }
        if (card.status() != CardStatus.ACTIVE) {
            throw new InvalidStateTransitionException("card", card.status(), CardStatus.BLOCKED);
        }
        // Truncated to what PostgreSQL stores, so the first response reports the same instant as every
        // later read of the card.
        return cards.blockIfActive(card.id(), command.reason(), clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
