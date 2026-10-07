package com.callverse.core.application.features.card.commands;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.Cards;
import com.callverse.core.application.interfaces.Cards.BlockOutcome;
import com.callverse.core.application.interfaces.Cards.CardRecord;
import com.callverse.core.application.interfaces.RealtimeEventPublisher;
import com.callverse.core.application.interfaces.SupervisionAlert;
import com.callverse.core.domain.enums.CardBlockReason;
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
 *
 * <p><strong>Supervisors are told about fraud.</strong> A block for {@code FRAUD_SUSPECTED} raises a
 * {@code CARD_BLOCKED_FRAUD} supervision alert — only from the call that actually blocked the card,
 * so a retry or a colleague's second click never raises a second alert.
 */
public class BlockCardCommandHandler {

    private final Cards cards;
    private final Clock clock;
    private final RealtimeEventPublisher events;

    public BlockCardCommandHandler(Cards cards, Clock clock, RealtimeEventPublisher events) {
        this.cards = Objects.requireNonNull(cards, "cards must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
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
        BlockOutcome outcome =
                cards.blockIfActive(card.id(), command.reason(), clock.instant().truncatedTo(ChronoUnit.MICROS));
        CardRecord blocked = outcome.card();
        if (outcome.blocked() && blocked.blockReason() == CardBlockReason.FRAUD_SUSPECTED) {
            events.publishSupervisionAlert(SupervisionAlert.cardBlockedForFraud(
                    blocked.id(), blocked.customerId(), blocked.panLast4(), blocked.blockedAt()));
        }
        return blocked;
    }
}
