package com.callverse.host.api.controllers;

import com.callverse.core.application.exceptions.AuthenticationRequiredException;
import com.callverse.core.application.features.conversation.commands.TakeNextConversationCommand;
import com.callverse.core.application.features.conversation.commands.TakeNextConversationCommandHandler;
import com.callverse.core.application.features.conversation.queries.GetQueuesQuery;
import com.callverse.core.application.features.conversation.queries.GetQueuesQueryHandler;
import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.application.interfaces.CurrentPrincipalProvider;
import com.callverse.host.api.dto.response.ConversationResponse;
import com.callverse.host.api.dto.response.QueuesResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The skill queues: how long they are, and taking the next conversation from one. */
@RestController
@RequestMapping(path = "/api/v1/queues", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Queues", description = "Skill queues")
@SecurityRequirement(name = "bearerAuth")
public class QueueController {

    private final GetQueuesQueryHandler getQueues;
    private final TakeNextConversationCommandHandler takeNext;
    private final CurrentPrincipalProvider principals;

    @GetMapping
    @PreAuthorize(Roles.STAFF)
    @Operation(
            operationId = "listQueues",
            summary = "Skill queues and how long they are",
            description = "Waiting conversations per skill and how long the oldest has waited. An advisor sees "
                    + "only the queues of skills they hold; supervisors and administrators see every queue.")
    public QueuesResponse list() {
        return QueuesResponse.from(getQueues.handle(new GetQueuesQuery(caller())));
    }

    @PostMapping("/{skill}/next")
    @PreAuthorize(Roles.ADVISOR)
    @Operation(
            operationId = "takeNextConversation",
            summary = "Take the next conversation from a queue",
            description = "Assigns the head of the queue (highest priority, then longest wait) to the calling "
                    + "advisor and records the wait and the SLA outcome. 204 when nothing waits. 403 for a skill "
                    + "the advisor does not hold; 409 ADVISOR_UNAVAILABLE at max_concurrent; 404 "
                    + "ADVISOR_PROFILE_NOT_FOUND when the login has no advisor profile.")
    @ApiResponse(responseCode = "200", description = "Assigned to the caller")
    @ApiResponse(responseCode = "204",
            description = "Nothing available now: the queue is empty, or its only contact is being written to at "
                    + "this instant (retry)")
    public ResponseEntity<ConversationResponse> next(@PathVariable String skill) {
        AuthenticatedPrincipal caller = caller();
        return takeNext.handle(new TakeNextConversationCommand(caller, skill))
                .map(taken -> {
                    log.info("AUDIT conversation={} assigned to advisor={} by user={}",
                            taken.id(), taken.advisorId(), caller.userId());
                    return ResponseEntity.ok(ConversationResponse.from(taken));
                })
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private AuthenticatedPrincipal caller() {
        return principals.current().orElseThrow(AuthenticationRequiredException::new);
    }
}
