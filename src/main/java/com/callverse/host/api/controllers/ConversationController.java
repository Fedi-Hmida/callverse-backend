package com.callverse.host.api.controllers;

import com.callverse.core.application.exceptions.AuthenticationRequiredException;
import com.callverse.core.application.features.conversation.commands.CloseConversationCommand;
import com.callverse.core.application.features.conversation.commands.CloseConversationCommandHandler;
import com.callverse.core.application.features.conversation.commands.EscalateConversationCommand;
import com.callverse.core.application.features.conversation.commands.EscalateConversationCommandHandler;
import com.callverse.core.application.features.conversation.commands.OpenConversationCommand;
import com.callverse.core.application.features.conversation.commands.OpenConversationCommandHandler;
import com.callverse.core.application.features.conversation.commands.PostMessageCommand;
import com.callverse.core.application.features.conversation.commands.PostMessageCommandHandler;
import com.callverse.core.application.features.conversation.queries.GetConversationQuery;
import com.callverse.core.application.features.conversation.queries.GetConversationQueryHandler;
import com.callverse.core.application.features.conversation.queries.GetHeldConversationsQuery;
import com.callverse.core.application.features.conversation.queries.GetHeldConversationsQueryHandler;
import com.callverse.core.application.features.conversation.queries.GetTranscriptQuery;
import com.callverse.core.application.features.conversation.queries.GetTranscriptQueryHandler;
import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.application.interfaces.ConversationLifecycle.ConversationRecord;
import com.callverse.core.application.interfaces.ConversationMessages.MessageRecord;
import com.callverse.core.application.interfaces.CurrentPrincipalProvider;
import com.callverse.core.application.interfaces.Escalations.EscalationOutcome;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.EscalationRaisedBy;
import com.callverse.host.api.dto.request.EscalationRequest;
import com.callverse.host.api.dto.request.MessageRequest;
import com.callverse.host.api.dto.request.OpenConversationRequest;
import com.callverse.host.api.dto.response.ConversationResponse;
import com.callverse.host.api.dto.response.EscalationResponse;
import com.callverse.host.api.dto.response.MessageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The life of a conversation: registered, read, written in, escalated, resolved or abandoned.
 *
 * <p>The role on each route is the first gate. The use case is the second: it checks that the caller
 * is this conversation's customer, its advisor or a supervisor, and answers anyone else 404, so a
 * conversation's existence never leaks to someone who may not read it.
 */
@RestController
@RequestMapping(path = "/api/v1/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Conversations", description = "The life of a customer conversation")
@SecurityRequirement(name = "bearerAuth")
public class ConversationController {

    private final OpenConversationCommandHandler openConversation;
    private final PostMessageCommandHandler postMessage;
    private final CloseConversationCommandHandler closeConversation;
    private final EscalateConversationCommandHandler escalateConversation;
    private final GetConversationQueryHandler getConversation;
    private final GetTranscriptQueryHandler getTranscript;
    private final GetHeldConversationsQueryHandler getHeldConversations;
    private final CurrentPrincipalProvider principals;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Roles.STAFF)
    @Operation(
            operationId = "openConversation",
            summary = "Register an incoming customer contact",
            description = "Puts a new live conversation in its skill's queue as QUEUED, scored from the "
                    + "customer's churn risk and segment and the contact's intent. Staff register contacts on "
                    + "the customer's behalf (switchboard, branch, chat entry). Unknown customer or skill: 404.")
    @ApiResponse(responseCode = "201", description = "Queued")
    public ResponseEntity<ConversationResponse> open(@Valid @RequestBody OpenConversationRequest request) {
        ConversationRecord opened = openConversation.handle(
                new OpenConversationCommand(request.customerId(), request.skill(), request.intent()));
        log.info("AUDIT conversation={} opened skill={} by user={}", opened.id(), opened.skill(), caller().userId());
        return ResponseEntity.created(URI.create("/api/v1/conversations/" + opened.id()))
                .body(ConversationResponse.from(opened));
    }

    @GetMapping("/mine")
    @PreAuthorize(Roles.ADVISOR)
    @Operation(
            operationId = "listMyConversations",
            summary = "The calling advisor's conversations",
            description = "Everything the advisor holds now: ASSIGNED, ACTIVE and ESCALATED, oldest first. "
                    + "404 ADVISOR_PROFILE_NOT_FOUND when the login has no advisor profile.")
    public ConversationResponse.ListResponse mine() {
        return ConversationResponse.ListResponse.from(getHeldConversations.handle(new GetHeldConversationsQuery(caller())));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.ANYONE)
    @Operation(
            operationId = "getConversation",
            summary = "One conversation",
            description = "For its customer, its assigned advisor, supervisors and administrators. Anyone else "
                    + "gets 404, exactly as for a conversation that does not exist.")
    public ConversationResponse get(@PathVariable UUID id) {
        return ConversationResponse.from(getConversation.handle(new GetConversationQuery(caller(), id)));
    }

    @GetMapping("/{id}/messages")
    @PreAuthorize(Roles.ANYONE)
    @Operation(
            operationId = "listMessages",
            summary = "The latest messages of a conversation",
            description = "The most recent `limit` messages (1 to 200, default 50), oldest first. Same readers as "
                    + "getConversation; new messages then arrive on /topic/conversation/{id}.")
    public MessageResponse.Transcript messages(
            @PathVariable UUID id, @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return MessageResponse.Transcript.from(id, getTranscript.handle(new GetTranscriptQuery(caller(), id, limit)));
    }

    @PostMapping(path = "/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Roles.PARTIES)
    @Operation(
            operationId = "postMessage",
            summary = "Write in a conversation",
            description = "The assigned advisor writes as ADVISOR, the conversation's customer as CUSTOMER; the "
                    + "server decides the sender. The advisor's first message moves ASSIGNED to ACTIVE. A closed "
                    + "conversation is 409 INVALID_STATE_TRANSITION; a conversation the caller may not read is 404.")
    @ApiResponse(responseCode = "201", description = "Written, and pushed to the conversation's topic")
    public ResponseEntity<MessageResponse> post(@PathVariable UUID id, @Valid @RequestBody MessageRequest request) {
        MessageRecord message = postMessage.handle(new PostMessageCommand(caller(), id, request.content()));
        return ResponseEntity.status(HttpStatus.CREATED).body(MessageResponse.from(message));
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize(Roles.AGENTS)
    @Operation(
            operationId = "resolveConversation",
            summary = "Resolve a conversation",
            description = "The assigned advisor resolves an ACTIVE conversation; a supervisor resolves an "
                    + "ESCALATED one, which also resolves its pending escalation. Records the handle time. "
                    + "Anything else is 403 or 409 INVALID_STATE_TRANSITION.")
    public ConversationResponse resolve(@PathVariable UUID id) {
        return close(id, ConversationStatus.RESOLVED);
    }

    @PostMapping("/{id}/abandon")
    @PreAuthorize(Roles.ANYONE)
    @Operation(
            operationId = "abandonConversation",
            summary = "Record that the customer left",
            description = "By the customer who left, the assigned advisor, a supervisor or an administrator, "
                    + "from QUEUED, ASSIGNED or ACTIVE. An escalated conversation is never abandoned (409).")
    public ConversationResponse abandon(@PathVariable UUID id) {
        return close(id, ConversationStatus.ABANDONED);
    }

    @PostMapping(path = "/{id}/escalations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Roles.ADVISOR)
    @Operation(
            operationId = "escalateConversation",
            summary = "Escalate a conversation to a supervisor",
            description = "Raised by the conversation's assigned ADVISOR; anyone else gets 404. Moves the "
                    + "conversation ACTIVE -> ESCALATED in the same transaction. Idempotent: a conversation that "
                    + "already has a pending escalation returns it with 200 instead of creating a second. Only an "
                    + "ACTIVE conversation can be escalated, else 409 INVALID_STATE_TRANSITION.")
    @ApiResponse(responseCode = "201", description = "Escalation created")
    @ApiResponse(responseCode = "200", description = "A pending escalation already existed and is returned")
    public ResponseEntity<EscalationResponse> escalate(
            @PathVariable UUID id, @Valid @RequestBody EscalationRequest request) {
        AuthenticatedPrincipal caller = caller();
        EscalationOutcome outcome = escalateConversation.handle(new EscalateConversationCommand(
                id, request.reason(), EscalationRaisedBy.ADVISOR, caller.userId()));
        if (outcome.created()) {
            log.info("AUDIT escalation={} conversation={} raised by user={}",
                    outcome.escalation().id(), id, caller.userId());
        }
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(EscalationResponse.from(outcome.escalation()));
    }

    private ConversationResponse close(UUID id, ConversationStatus target) {
        AuthenticatedPrincipal caller = caller();
        ConversationRecord closed = closeConversation.handle(new CloseConversationCommand(caller, id, target));
        // The schema has no actor column on conversation; the log is the record of who closed it.
        log.info("AUDIT conversation={} status={} by user={}", id, closed.status(), caller.userId());
        return ConversationResponse.from(closed);
    }

    /** The route's role rule guarantees a caller; an absent one is answered 401, never a 500. */
    private AuthenticatedPrincipal caller() {
        return principals.current().orElseThrow(AuthenticationRequiredException::new);
    }
}
