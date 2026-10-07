package com.callverse.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.callverse.core.application.interfaces.Cards;
import com.callverse.core.domain.entities.Card;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.enums.CardBlockReason;
import com.callverse.core.domain.enums.CardStatus;
import com.callverse.core.domain.exceptions.InvalidStateTransitionException;
import com.callverse.persistence.AbstractPersistenceTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The locked step of card blocking, called directly. The handler checks the status first, but the
 * card can change between that check and the lock; the lock is where the rule must hold.
 */
@Transactional
class CardsAdapterTest extends AbstractPersistenceTest {

    @Autowired EntityManager em;
    @Autowired Cards cards;

    private Card card(CardStatus status) {
        WorkspaceFixtures fixtures = new WorkspaceFixtures(em);
        Customer customer = fixtures.customer("ADP-" + WorkspaceFixtures.suffix(), "Marseille");
        Card card = fixtures.card(fixtures.account(customer, WorkspaceFixtures.iban()), status);
        em.flush();
        em.clear();
        return card;
    }

    @Test
    @DisplayName("a card that expired before the lock was taken is refused under the lock, not reported as 200")
    void expiredUnderLockIsRefused() {
        Card card = card(CardStatus.EXPIRED);

        assertThatThrownBy(() -> cards.blockIfActive(card.getId(), CardBlockReason.LOST, Instant.now()))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("an already blocked card is returned unchanged under the lock")
    void blockedUnderLockIsUnchanged() {
        Card card = card(CardStatus.ACTIVE);
        Instant first = Instant.parse("2026-10-01T16:00:00Z");
        cards.blockIfActive(card.getId(), CardBlockReason.STOLEN, first);
        em.flush();
        em.clear();

        Cards.CardRecord again = cards.blockIfActive(card.getId(), CardBlockReason.LOST, Instant.now()).card();

        assertThat(again.blockReason()).isEqualTo(CardBlockReason.STOLEN);
        assertThat(again.blockedAt()).isEqualTo(first);
    }
}
