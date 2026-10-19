package com.callverse.host.api.controllers;

import com.callverse.core.application.features.conversation.queries.GetAiExplanationQueryHandler;
import com.callverse.core.application.interfaces.ConversationAiExplanations.Explanation;
import com.callverse.core.application.interfaces.CurrentPrincipalProvider;
import com.callverse.core.application.exceptions.AuthenticationRequiredException;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ConversationAiExplanationController {
    private final GetAiExplanationQueryHandler query;
    private final CurrentPrincipalProvider principals;

    @GetMapping("/api/v1/conversations/{id}/messages/{messageId}/explanation")
    @PreAuthorize(Roles.STAFF)
    @Operation(operationId = "getAiMessageExplanation", summary = "Read sources, checks and recorded decision for an AI reply; staff only")
    public Explanation get(@PathVariable UUID id, @PathVariable long messageId) {
        return query.handle(principals.current().orElseThrow(AuthenticationRequiredException::new), id, messageId);
    }
}
