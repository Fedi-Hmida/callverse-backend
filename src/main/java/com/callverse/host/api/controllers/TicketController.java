package com.callverse.host.api.controllers;

import com.callverse.core.application.features.ticket.commands.OpenTicketCommand;
import com.callverse.core.application.features.ticket.commands.OpenTicketCommandHandler;
import com.callverse.core.application.interfaces.CurrentPrincipalProvider;
import com.callverse.core.application.interfaces.Tickets.OpenedTicket;
import com.callverse.host.api.dto.request.OpenTicketRequest;
import com.callverse.host.api.dto.response.TicketResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Support tickets opened by staff during a call. */
@RestController
@RequestMapping(path = "/api/v1/tickets", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Tickets", description = "Support tickets")
public class TicketController {

    private final OpenTicketCommandHandler openTicket;
    private final CurrentPrincipalProvider principals;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Roles.AGENTS)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            operationId = "openTicket",
            summary = "Open a support ticket",
            description = "Always created OPEN; severity 1 to 5, default 3. When conversationId is given it "
                    + "must belong to customerId, else 400 CONVERSATION_CUSTOMER_MISMATCH. Unknown customer or "
                    + "conversation: 404. Advisors and supervisors only.")
    @ApiResponse(responseCode = "201", description = "Ticket created")
    public ResponseEntity<TicketResponse> open(@Valid @RequestBody OpenTicketRequest request) {
        OpenedTicket ticket = openTicket.handle(new OpenTicketCommand(
                request.customerId(),
                request.conversationId(),
                request.category(),
                request.title(),
                request.description(),
                request.severity()));
        log.info("AUDIT ticket={} customer={} category={} opened by user={}",
                ticket.id(), ticket.customerId(), ticket.category(), actor());
        return ResponseEntity.status(HttpStatus.CREATED).body(TicketResponse.from(ticket));
    }

    /** The authenticated user's id for the audit line; the route guarantees one exists. */
    private String actor() {
        return principals.current().map(p -> p.userId().toString()).orElse("unknown");
    }

}
