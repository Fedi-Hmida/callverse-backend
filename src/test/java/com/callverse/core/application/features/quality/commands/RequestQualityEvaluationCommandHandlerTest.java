package com.callverse.core.application.features.quality.commands;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import com.callverse.core.application.interfaces.ConversationMessages;
import com.callverse.core.application.interfaces.ConversationMessages.MessageRecord;
import com.callverse.core.application.interfaces.QualityEvaluationQueue;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.MessageSender;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestQualityEvaluationCommandHandlerTest {
    private final UUID id = UUID.randomUUID();
    private final ConversationLifecycle conversations = mock(ConversationLifecycle.class);
    private final ConversationMessages messages = mock(ConversationMessages.class);
    private final QualityEvaluationQueue queue = mock(QualityEvaluationQueue.class);
    private final RequestQualityEvaluationCommandHandler handler = new RequestQualityEvaluationCommandHandler(conversations, messages, queue);

    private void conversation(ConversationStatus status) {
        var record = mock(ConversationRecord.class);
        when(record.status()).thenReturn(status);
        when(conversations.find(id)).thenReturn(Optional.of(record));
    }
    @Test void rejectsMissingConversation() {
        when(conversations.find(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> handler.handle(id)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(queue);
    }
    @Test void rejectsUnresolvedConversation() {
        conversation(ConversationStatus.ACTIVE);
        assertThatThrownBy(() -> handler.handle(id)).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(messages, queue);
    }
    @Test void rejectsTranscriptWithoutAdvisorReply() {
        conversation(ConversationStatus.RESOLVED);
        when(messages.recent(id, 500)).thenReturn(List.of(new MessageRecord(1, id, MessageSender.CUSTOMER, "Bonjour", Instant.EPOCH)));
        assertThatThrownBy(() -> handler.handle(id)).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(queue);
    }
    @Test void queuesResolvedExchangeWithoutCallingModelInHttpThread() {
        conversation(ConversationStatus.RESOLVED);
        when(messages.recent(id, 500)).thenReturn(List.of(
                new MessageRecord(1, id, MessageSender.CUSTOMER, "Bonjour", Instant.EPOCH),
                new MessageRecord(2, id, MessageSender.ADVISOR, "Comment vous aider ?", Instant.EPOCH)));
        handler.handle(id);
        verify(queue).enqueue(id);
    }
}
