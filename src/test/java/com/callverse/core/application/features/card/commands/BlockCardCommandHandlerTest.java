package com.callverse.core.application.features.card.commands;

import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.application.RecordingPublisher;
import com.callverse.core.application.interfaces.Cards;
import com.callverse.core.application.interfaces.SupervisionAlert;
import com.callverse.core.domain.enums.CardBlockReason;
import com.callverse.core.domain.enums.CardNetwork;
import com.callverse.core.domain.enums.CardStatus;
import com.callverse.core.domain.enums.CardType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A supervisor is alerted when a card is newly blocked for suspected fraud, and only then. */
class BlockCardCommandHandlerTest {

    private static final UUID CARD = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T16:53:00Z"), ZoneOffset.UTC);

    /** One card, in a chosen state; blocks it the way the locked adapter step does. */
    private static final class FakeCards implements Cards {
        CardStatus status;
        CardBlockReason reason;
        Instant blockedAt;

        FakeCards(CardStatus status) {
            this.status = status;
        }

        private CardRecord record() {
            return new CardRecord(CARD, UUID.randomUUID(), CUSTOMER, "4242", CardNetwork.VISA, CardType.DEBIT,
                    status, LocalDate.of(2029, 12, 31), blockedAt, reason);
        }

        @Override
        public Optional<CardRecord> find(UUID cardId) {
            return Optional.of(record());
        }

        @Override
        public BlockOutcome blockIfActive(UUID cardId, CardBlockReason why, Instant at) {
            if (status != CardStatus.ACTIVE) {
                return new BlockOutcome(record(), false);
            }
            status = CardStatus.BLOCKED;
            reason = why;
            blockedAt = at;
            return new BlockOutcome(record(), true);
        }
    }

    private final RecordingPublisher publisher = new RecordingPublisher();

    @Test
    @DisplayName("a fraud block on an active card raises one CARD_BLOCKED_FRAUD alert")
    void fraudBlockAlerts() {
        new BlockCardCommandHandler(new FakeCards(CardStatus.ACTIVE), CLOCK, publisher)
                .handle(new BlockCardCommand(CARD, CardBlockReason.FRAUD_SUSPECTED));

        assertThat(publisher.alerts).hasSize(1);
        SupervisionAlert alert = publisher.alerts.get(0);
        assertThat(alert.type()).isEqualTo(SupervisionAlert.Type.CARD_BLOCKED_FRAUD);
        assertThat(alert.cardId()).isEqualTo(CARD);
        assertThat(alert.customerId()).isEqualTo(CUSTOMER);
        assertThat(alert.cardLast4()).isEqualTo("4242");
        assertThat(alert.occurredAt()).isEqualTo(Instant.parse("2026-10-01T16:53:00Z"));
    }

    @Test
    @DisplayName("a block for another reason raises nothing")
    void otherReasonsDoNotAlert() {
        new BlockCardCommandHandler(new FakeCards(CardStatus.ACTIVE), CLOCK, publisher)
                .handle(new BlockCardCommand(CARD, CardBlockReason.LOST));

        assertThat(publisher.alerts).isEmpty();
    }

    @Test
    @DisplayName("a card already blocked raises nothing: a repeat is not a new event")
    void repeatDoesNotAlert() {
        FakeCards cards = new FakeCards(CardStatus.ACTIVE);
        BlockCardCommandHandler handler = new BlockCardCommandHandler(cards, CLOCK, publisher);
        handler.handle(new BlockCardCommand(CARD, CardBlockReason.FRAUD_SUSPECTED));
        handler.handle(new BlockCardCommand(CARD, CardBlockReason.FRAUD_SUSPECTED));

        assertThat(publisher.alerts).hasSize(1);
    }
}
