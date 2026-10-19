package com.callverse.core.application.features.conversation.queries;

import com.callverse.core.application.exceptions.ActionNotPermittedException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.features.conversation.ConversationAccessPolicy;
import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.application.interfaces.ConversationAiExplanations;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.domain.enums.UserRole;
import java.util.UUID;

public record GetAiExplanationQueryHandler(ConversationLifecycle conversations, ConversationAiExplanations explanations) {
    public ConversationAiExplanations.Explanation handle(AuthenticatedPrincipal caller, UUID id, long messageId) {
        if (caller.role() == UserRole.CUSTOMER) throw new ActionNotPermittedException("Réservé aux équipes internes.");
        conversations.find(id).filter(conversation -> ConversationAccessPolicy.canRead(caller, conversation))
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", id));
        return explanations.find(id, messageId)
                .orElseThrow(() -> new ResourceNotFoundException("AI message", messageId));
    }
}
