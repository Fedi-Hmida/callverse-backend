package com.callverse.core.application.features.quality.commands;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationMessages;
import com.callverse.core.application.interfaces.QualityEvaluationQueue;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.MessageSender;
import java.util.UUID;

public record RequestQualityEvaluationCommandHandler(
        ConversationLifecycle conversations, ConversationMessages messages, QualityEvaluationQueue queue) {
    public void handle(UUID id) {
        var conversation = conversations.find(id)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", id));
        if (conversation.status() != ConversationStatus.RESOLVED) {
            throw new InvalidRequestException("Resolve the conversation before requesting quality analysis.");
        }
        var transcript = messages.recent(id, 500).stream()
                .filter(m -> m.sender() == MessageSender.CUSTOMER || m.sender() == MessageSender.ADVISOR).toList();
        if (transcript.size() < 2 || transcript.stream().noneMatch(m -> m.sender() == MessageSender.ADVISOR)) {
            throw new InvalidRequestException("Quality analysis requires at least two messages, including an advisor reply.");
        }
        queue.enqueue(id);
    }
}
