package com.callverse.host.api.controllers;

import com.callverse.core.application.features.conversation.commands.EscalateConversationCommand;
import com.callverse.core.application.features.conversation.commands.EscalateConversationCommandHandler;
import com.callverse.core.application.interfaces.CurrentPrincipalProvider;
import com.callverse.core.application.interfaces.Escalations.EscalationOutcome;
import com.callverse.core.domain.enums.EscalationRaisedBy;
import com.callverse.host.api.dto.request.EscalationRequest;
import com.callverse.host.api.dto.response.EscalationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Conversation actions taken by staff. */
@RestController
@RequestMapping(path = "/api/v1/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Conversations", description = "Conversation actions")
public class ConversationController {

    private final EscalateConversationCommandHandler escalateConversation;
    private final CurrentPrincipalProvider principals;

    @PostMapping(path = "/{id}/escalations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Roles.ADVISOR)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            operationId = "escalateConversation",
            summary = "Escalate a conversation to a supervisor",
            description = "Raised by ADVISOR. Idempotent: a conversation that already has a pending escalation "
                    + "returns it with 200 instead of creating a second. Only an ACTIVE conversation can be "
                    + "escalated, else 409 INVALID_STATE_TRANSITION; an unknown conversation is 404. Advisors only: a "
                    + "supervisor is who an escalation goes to.")
    @ApiResponse(responseCode = "201", description = "Escalation created")
    @ApiResponse(responseCode = "200", description = "A pending escalation already existed and is returned")
    public ResponseEntity<EscalationResponse> escalate(
            @PathVariable UUID id, @Valid @RequestBody EscalationRequest request) {
        EscalationOutcome outcome = escalateConversation.handle(
                new EscalateConversationCommand(id, request.reason(), EscalationRaisedBy.ADVISOR));
        if (outcome.created()) {
            log.info("AUDIT escalation={} conversation={} raised by user={}",
                    outcome.escalation().id(), id, actor());
        }
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(EscalationResponse.from(outcome.escalation()));
    }

    /** The authenticated user's id for the audit line; the route guarantees one exists. */
    private String actor() {
        return principals.current().map(p -> p.userId().toString()).orElse("unknown");
    }

}
