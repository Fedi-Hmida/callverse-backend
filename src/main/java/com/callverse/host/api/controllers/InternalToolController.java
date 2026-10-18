package com.callverse.host.api.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.callverse.core.application.interfaces.SupportOperations;
import com.callverse.core.application.interfaces.SupportOperations.Credit;
import com.callverse.core.application.interfaces.SupportOperations.CreditRequest;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import com.callverse.core.application.features.customer.queries.GetCustomerProfileQuery;
import com.callverse.core.application.features.customer.queries.GetCustomerProfileQueryHandler;
import com.callverse.core.application.features.customer.queries.GetRecentTransactionsQuery;
import com.callverse.core.application.features.customer.queries.GetRecentTransactionsQueryHandler;
import com.callverse.core.application.features.conversation.commands.EscalateConversationCommand;
import com.callverse.core.application.features.conversation.commands.EscalateConversationCommandHandler;
import com.callverse.core.application.features.incident.queries.GetServiceStatusQuery;
import com.callverse.core.application.features.incident.queries.GetServiceStatusQueryHandler;
import com.callverse.core.application.features.ticket.commands.OpenTicketCommand;
import com.callverse.core.application.features.ticket.commands.OpenTicketCommandHandler;
import com.callverse.core.application.interfaces.Escalations.EscalationOutcome;
import com.callverse.core.application.interfaces.ServiceIncidents.ActiveIncident;
import com.callverse.core.application.interfaces.Tickets.OpenedTicket;
import com.callverse.core.domain.enums.BankingService;
import com.callverse.core.domain.enums.EscalationRaisedBy;
import com.callverse.host.api.dto.request.EscalationRequest;
import com.callverse.host.api.dto.request.internal.InternalOpenTicketRequest;
import com.callverse.host.api.dto.response.CustomerResponse;
import com.callverse.host.api.dto.response.EscalationResponse;
import com.callverse.host.api.dto.response.ServiceIncidentsResponse;
import com.callverse.host.api.dto.response.TicketResponse;
import com.callverse.host.api.dto.response.TransactionsResponse;
import com.callverse.host.api.errorhandling.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Service-key-protected routes used by the AI service. */
@RestController
@RequestMapping(path = "${internal.api-prefix:/internal}", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Internal tools", description = "Banking tools for the AI service")
public class InternalToolController {

    private final GetCustomerProfileQueryHandler getCustomerProfile;
    private final GetRecentTransactionsQueryHandler getRecentTransactions;
    private final GetServiceStatusQueryHandler getServiceStatus;
    private final OpenTicketCommandHandler openTicket;
    private final EscalateConversationCommandHandler escalateConversation;
    private final SupportOperations support;

    @GetMapping("/customers/{id}")
    @SecurityRequirement(name = "serviceKey")
    @Operation(
            operationId = "getInternalCustomer",
            summary = "Read a customer for the AI service",
            description = "Returns customer accounts and cards with masked IBANs. Churn risk, simulation "
                    + "flags and contact details are not exposed.")
    public CustomerResponse customer(@PathVariable UUID id) {
        return CustomerResponse.from(getCustomerProfile.handle(new GetCustomerProfileQuery(id)));
    }

    @GetMapping("/customers/{id}/transactions")
    @SecurityRequirement(name = "serviceKey")
    @Operation(
            operationId = "getInternalCustomerTransactions",
            summary = "List a customer's recent transactions for the AI service",
            description = "Returns recent transactions, newest first, with IBANs masked in free-text fields.")
    public TransactionsResponse transactions(
            @PathVariable UUID id, @RequestParam(required = false) Integer n) {
        return TransactionsResponse.of(
                id, getRecentTransactions.handle(new GetRecentTransactionsQuery(id, n)));
    }

    @GetMapping("/service-incidents")
    @SecurityRequirement(name = "serviceKey")
    @Operation(
            operationId = "getInternalServiceIncidents",
            summary = "Check active banking-service incidents",
            description = "Returns active live incidents, optionally filtered by banking service. The service "
                    + "parameter is an enum such as CARD_PAYMENTS or MOBILE_APP.")
    public ServiceIncidentsResponse serviceIncidents(@RequestParam(required = false) BankingService service) {
        List<ActiveIncident> incidents = getServiceStatus.handle(new GetServiceStatusQuery(null, null)).stream()
                .filter(incident -> service == null || incident.service() == service)
                .toList();
        return ServiceIncidentsResponse.of(incidents);
    }

    @PostMapping(path = "/tickets", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = "serviceKey")
    @Operation(
            operationId = "openInternalTicket",
            summary = "Open a ticket for the AI service",
            description = "Creates an OPEN ticket. conversationId is required and must belong to customerId.")
    @ApiResponse(responseCode = "201", description = "Ticket created")
    public ResponseEntity<TicketResponse> openTicket(@Valid @RequestBody InternalOpenTicketRequest request) {
        OpenedTicket ticket = openTicket.handle(new OpenTicketCommand(
                request.customerId(),
                request.conversationId(),
                request.category(),
                request.title(),
                request.description(),
                request.severity()));
        log.info("AUDIT ticket={} customer={} category={} opened by service=AI",
                ticket.id(), ticket.customerId(), ticket.category());
        return ResponseEntity.status(HttpStatus.CREATED).body(TicketResponse.from(ticket));
    }

    @PostMapping(path = "/conversations/{id}/escalate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = "serviceKey")
    @Operation(
            operationId = "escalateInternalConversation",
            summary = "Escalate a conversation to a supervisor",
            description = "Records the escalation as raised by AI and moves the conversation to ESCALATED. "
                    + "Only an ACTIVE conversation can be escalated; a pending escalation is returned idempotently.")
    @ApiResponse(responseCode = "201", description = "Escalation created")
    @ApiResponse(responseCode = "200", description = "A pending escalation already existed and is returned")
    public ResponseEntity<EscalationResponse> escalate(
            @PathVariable UUID id, @Valid @RequestBody EscalationRequest request) {
        EscalationOutcome outcome = escalateConversation.handle(new EscalateConversationCommand(
                id, request.reason(), EscalationRaisedBy.AI, null));
        if (outcome.created()) {
            log.info("AUDIT escalation={} conversation={} raised by service=AI",
                    outcome.escalation().id(), id);
        }
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(EscalationResponse.from(outcome.escalation()));
    }

    @PostMapping(path = "/credits", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = "serviceKey")
    @Operation(
            operationId = "applyInternalCredit",
            summary = "Apply a commercial credit",
            description = "Books an idempotent goodwill refund to an active EUR current account. Customer and conversation must match. "
                    + "The assigned advisor's ceiling applies to all customer credits over the preceding 24 hours; the service cannot approve an override.")
    public Credit applyCredit(@Valid @RequestBody InternalCredit request) {
        return support.grantFromService(new CreditRequest(request.customerId(),request.conversationId(),request.accountId(),
                request.amount(),request.reason(),request.requestId()));
    }

    public record InternalCredit(@NotNull @JsonAlias("customer_id") UUID customerId,
                                 @NotNull @JsonAlias("conversation_id") UUID conversationId,
                                 @JsonAlias("account_id") UUID accountId,
                                 @NotNull @DecimalMin("0.01") @Digits(integer=6,fraction=2) BigDecimal amount,
                                 @NotBlank @Size(max=255) String reason,
                                 @NotNull @JsonAlias("request_id") UUID requestId) {}
}
