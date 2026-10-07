package com.callverse.infrastructure.persistence;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.Cards;
import com.callverse.core.domain.entities.Card;
import com.callverse.core.domain.enums.CardBlockReason;
import com.callverse.core.domain.enums.CardStatus;
import com.callverse.core.domain.exceptions.InvalidStateTransitionException;
import com.callverse.infrastructure.persistence.repositories.CardRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Backs {@link Cards} with Spring Data. Package-private: callers name the port. */
@Component
@RequiredArgsConstructor
class CardsAdapter implements Cards {

    private final CardRepository cards;

    @Override
    @Transactional(readOnly = true)
    public Optional<CardRecord> find(UUID cardId) {
        return cards.findById(cardId).map(CardsAdapter::toRecord);
    }

    @Override
    @Transactional
    public BlockOutcome blockIfActive(UUID cardId, CardBlockReason reason, Instant at) {
        Card card = cards.findByIdForUpdate(cardId)
                .orElseThrow(() -> new ResourceNotFoundException("Card", cardId));
        if (card.getStatus() == CardStatus.ACTIVE) {
            // Status, time and reason together: chk_card_blocked refuses a block missing either.
            card.setStatus(CardStatus.BLOCKED);
            card.setBlockedAt(at);
            card.setBlockReason(reason);
            return new BlockOutcome(toRecord(card), true);
        } else if (card.getStatus() != CardStatus.BLOCKED) {
            // The card expired or was cancelled between the handler's check and this lock.
            throw new InvalidStateTransitionException("card", card.getStatus(), CardStatus.BLOCKED);
        }
        return new BlockOutcome(toRecord(card), false);
    }

    private static CardRecord toRecord(Card card) {
        return new CardRecord(
                card.getId(),
                // Reading the id of a lazy proxy does not initialise it.
                card.getAccount().getId(),
                // Inside the adapter's transaction, so walking the lazy account is safe.
                card.getAccount().getCustomer().getId(),
                card.getPanLast4(),
                card.getNetwork(),
                card.getType(),
                card.getStatus(),
                card.getExpiresOn(),
                card.getBlockedAt(),
                card.getBlockReason());
    }
}
