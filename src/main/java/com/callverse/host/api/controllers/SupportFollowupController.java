package com.callverse.host.api.controllers;

import com.callverse.core.application.exceptions.AuthenticationRequiredException;
import com.callverse.core.application.interfaces.CurrentPrincipalProvider;
import com.callverse.core.application.interfaces.SupportOperations;
import com.callverse.core.application.interfaces.SupportOperations.*;
import com.callverse.core.domain.enums.TicketStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path="/api/v1",produces="application/json")
@RequiredArgsConstructor
@SecurityRequirement(name="bearerAuth")
public class SupportFollowupController {
    private final SupportOperations operations;
    private final CurrentPrincipalProvider principals;
    private com.callverse.core.application.interfaces.AuthenticatedPrincipal caller() {
        return principals.current().orElseThrow(AuthenticationRequiredException::new);
    }
    @GetMapping("/tickets") @PreAuthorize(Roles.ANYONE)
    @Operation(operationId="listSupportTickets",summary="List support tickets; customers see only their own")
    public TicketPage tickets(@RequestParam(required=false) UUID customerId,@RequestParam(required=false) TicketStatus status,
                              @RequestParam(defaultValue="0") int page) { return operations.tickets(caller(),customerId,status,page); }
    @GetMapping("/tickets/{id}") @PreAuthorize(Roles.ANYONE)
    @Operation(operationId="getSupportTicket",summary="Read a ticket and its follow-up comments")
    public TicketView ticket(@PathVariable UUID id) { return operations.ticket(caller(),id); }
    public record ChangeTicket(@NotNull TicketStatus expectedStatus,@NotNull TicketStatus status) {}
    @PutMapping("/tickets/{id}/status") @PreAuthorize(Roles.ANYONE)
    @Operation(operationId="changeSupportTicketStatus",summary="Staff progress/resolve/reopen; owning customer accepts closure")
    public TicketView status(@PathVariable UUID id,@Valid @RequestBody ChangeTicket request) {
        return operations.transition(caller(),id,request.expectedStatus(),request.status());
    }
    public record AddComment(@NotBlank @Size(max=4000) String content) {}
    @PostMapping("/tickets/{id}/comments") @PreAuthorize(Roles.ANYONE) @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId="addSupportTicketComment",summary="Add a comment visible to the customer and staff")
    public Comment comment(@PathVariable UUID id,@Valid @RequestBody AddComment request) {
        return operations.comment(caller(),id,request.content());
    }
    @GetMapping("/credits") @PreAuthorize(Roles.ANYONE)
    @Operation(operationId="listCommercialCredits",summary="Read granted goodwill credits; customers see only their own")
    public List<Credit> credits(@RequestParam(required=false) UUID customerId) { return operations.credits(caller(),customerId); }
    public record GrantCredit(@NotNull UUID customerId,@NotNull UUID conversationId,UUID accountId,
                              @NotNull @DecimalMin("0.01") @Digits(integer=6,fraction=2) BigDecimal amount,
                              @NotBlank @Size(max=255) String reason,@NotNull UUID requestId) {
        public CreditRequest toRequest() { return new CreditRequest(customerId,conversationId,accountId,amount,reason,requestId); }
    }
    @PostMapping("/credits") @PreAuthorize(Roles.STAFF) @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId="grantCommercialCredit",summary="Book an idempotent EUR goodwill credit; supervision may approve above rolling ceiling")
    public Credit grant(@Valid @RequestBody GrantCredit request) { return operations.grant(caller(),request.toRequest()); }
}
