package com.callverse.host.api.controllers;

import com.callverse.core.application.features.customer.CustomerPortalHandler;
import com.callverse.core.application.features.customer.RemoveOwnConversationCommandHandler;
import com.callverse.core.domain.enums.Intent;
import com.callverse.host.api.dto.response.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name="bearerAuth")
public class CustomerPortalController {
    private final CustomerPortalHandler portal;
    private final RemoveOwnConversationCommandHandler removal;
    public record ContactRequest(@NotBlank @Size(max=30) String skill, Intent intent) {}

    @GetMapping("/customer") @Operation(operationId="getOwnCustomer",summary="Read my customer profile and accounts")
    public CustomerResponse profile() { return CustomerResponse.from(portal.profile()); }
    @GetMapping("/accounts") @Operation(operationId="listOwnAccounts",summary="List my real bank accounts")
    public List<CustomerResponse.Account> accounts() { return profile().accounts(); }
    @GetMapping("/transactions") @Operation(operationId="listOwnTransactions",summary="List my recent transactions")
    public TransactionsResponse transactions(@RequestParam(defaultValue="20") int count) {
        var profile = portal.profile();
        return TransactionsResponse.of(profile.id(),portal.recent(count));
    }
    @PostMapping("/conversations") @Operation(operationId="openOwnConversation",summary="Open a contact for myself",
            description="One live conversation per customer: while one is queued, assigned, active or escalated, "
                    + "409 CONVERSATION_ALREADY_OPEN names it in the message; close or delete it first.")
    public ResponseEntity<ConversationResponse> open(@Valid @RequestBody ContactRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ConversationResponse.from(portal.open(request.skill(),request.intent())));
    }
    @DeleteMapping("/conversations/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId="deleteOwnConversation",summary="Remove one of my conversations from my space",
            description="The bank keeps the record. A live conversation is ended as ABANDONED; the advisor and the "
                    + "supervisor see a SYSTEM notice in the transcript and a CONVERSATION_DELETED_BY_CUSTOMER event "
                    + "on the skill's queue topic. Escalated: 409 CONVERSATION_UNDER_SUPERVISION. Not mine, simulated "
                    + "or already removed: 404.")
    public void delete(@PathVariable java.util.UUID id) { removal.handle(id); }
    @GetMapping(value="/statement",produces="text/csv")
    @Operation(operationId="downloadOwnStatement",summary="Download my movements for a date range as CSV")
    public ResponseEntity<byte[]> statement(@RequestParam LocalDate from,@RequestParam LocalDate to) {
        var rows = portal.statement(from,to);
        String csv = "Date UTC;Type;Montant;Devise;Libelle;Contrepartie;Statut\r\n" + rows.stream().map(t ->
            cell(t.bookedAt().atZone(ZoneOffset.UTC).toLocalDate().toString())+";"+cell(t.type().name())+";"+
            cell(t.amount().toPlainString())+";"+cell(t.currency())+";"+cell(IbanMask.redact(t.label()))+";"+
            cell(IbanMask.redact(t.counterparty()))+";"+cell(t.status().name())).collect(Collectors.joining("\r\n"));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=callverse-movements-"+from+"-"+to+".csv")
                .header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(new MediaType("text","csv",StandardCharsets.UTF_8))
                .body(("\ufeff"+csv).getBytes(StandardCharsets.UTF_8));
    }
    private static String cell(String value) {
        String clean=value==null?"":value;
        // CSV formula injection guard, including whitespace before a formula character.
        String leading = clean.stripLeading();
        if (!leading.isEmpty() && "=+@-".indexOf(leading.charAt(0)) >= 0) clean="'"+clean;
        return "\""+clean.replace("\"","\"\"")+"\"";
    }
}
