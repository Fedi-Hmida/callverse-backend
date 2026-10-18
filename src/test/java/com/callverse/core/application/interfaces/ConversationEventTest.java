package com.callverse.core.application.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.application.interfaces.ConversationMessages.MessageRecord;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.MessageSender;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The live MESSAGE_POSTED payload says whether the AI wrote the message, as the REST transcript does. */
class ConversationEventTest {

    @Test
    void aMessageEventCarriesTheAiFlagAndAStatusEventLeavesItNull() {
        UUID conversation = UUID.randomUUID();
        MessageRecord aiTurn = new MessageRecord(
                5L, conversation, MessageSender.ADVISOR, "Je vérifie.", Instant.EPOCH, true);

        assertThat(ConversationEvent.messagePosted(aiTurn, ConversationStatus.ACTIVE).aiGenerated()).isTrue();
        assertThat(ConversationEvent.messagePosted(
                        new MessageRecord(6L, conversation, MessageSender.CUSTOMER, "Merci", Instant.EPOCH),
                        ConversationStatus.ACTIVE)
                .aiGenerated()).isFalse();
        assertThat(ConversationEvent.statusChanged(conversation, ConversationStatus.RESOLVED, Instant.EPOCH)
                .aiGenerated()).isNull();
    }
}
