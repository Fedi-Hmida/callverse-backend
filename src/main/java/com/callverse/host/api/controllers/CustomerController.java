package com.callverse.host.api.controllers;

import com.callverse.core.application.features.customer.queries.FindCustomerByReferenceQuery;
import com.callverse.core.application.features.customer.queries.FindCustomerByReferenceQueryHandler;
import com.callverse.core.application.features.customer.queries.GetCustomerProfileQuery;
import com.callverse.core.application.features.customer.queries.GetCustomerProfileQueryHandler;
import com.callverse.core.application.features.customer.queries.GetRecentTransactionsQuery;
import com.callverse.core.application.features.customer.queries.GetRecentTransactionsQueryHandler;
import com.callverse.core.application.interfaces.CustomerProfile;
import com.callverse.host.api.dto.response.CustomerResponse;
import com.callverse.host.api.dto.response.IbanMask;
import com.callverse.host.api.dto.response.TransactionsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer records, for staff.
 *
 * <p><strong>Two layers decide access, and both are necessary.</strong> The filter chain decides
 * reachability — under any profile other than {@code dev} the default chain is deny-by-default, so
 * these routes carry an explicit {@code authenticated()} rule in {@code SecurityConfiguration}.
 * Method security then decides the role. Removing either one produces a refusal, which is why the
 * tests assert a successful staff read as well as a refused CUSTOMER one.
 *
 * <p><strong>Staff read any customer — a documented stopping point, not a finished design.</strong>
 * The advisor console needs to open the customer on the line, and the {@code app_user → customer}
 * and advisor → conversation hops that ownership rules begin with are not expressible until
 * {@code customer.user_id} carries a {@code UNIQUE} constraint (S-1). Until the ownership phase,
 * every advisor can read every customer through these routes; CUSTOMER has no path here at all.
 */
@RestController
// produces is pinned so the published contract says application/json rather than the */*
// springdoc infers when a controller stays silent.
@RequestMapping(path = "/api/v1/customers", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Customers", description = "Customer records and history, for staff")
public class CustomerController {

    private final GetCustomerProfileQueryHandler getCustomerProfile;
    private final FindCustomerByReferenceQueryHandler findCustomerByReference;
    private final GetRecentTransactionsQueryHandler getRecentTransactions;

    @GetMapping
    @PreAuthorize(Roles.STAFF)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            operationId = "findCustomerByReference",
            summary = "Find a customer by reference",
            description = "Exact match on the bank's customer reference, the one a customer reads out on the "
                    + "phone. Unknown reference: 404 RESOURCE_NOT_FOUND. Staff only; IBANs are masked.")
    public CustomerResponse byReference(@RequestParam String externalRef) {
        return toResponse(findCustomerByReference.handle(new FindCustomerByReferenceQuery(externalRef)));
    }

    @GetMapping("/{id}/transactions")
    @PreAuthorize(Roles.STAFF)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            operationId = "listCustomerTransactions",
            summary = "List a customer's recent transactions",
            description = "Across every account, newest first; count 1 to 50, default 10. Any IBAN inside a "
                    + "counterparty is masked. Unknown customer: 404, never an empty list. Staff only.")
    public TransactionsResponse transactions(
            @PathVariable UUID id, @RequestParam(required = false) Integer count) {
        return TransactionsResponse.of(id, getRecentTransactions.handle(new GetRecentTransactionsQuery(id, count)));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.STAFF)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            operationId = "getCustomer",
            summary = "Read a customer",
            description =
                    "Returns the customer, their accounts and the products they hold. Staff only; "
                            + "ownership rules are not yet expressible, so any advisor can read any "
                            + "customer until they are. IBANs are masked; churn risk is deliberately not "
                            + "exposed. Unknown id: 404 RESOURCE_NOT_FOUND; a CUSTOMER caller: 403.")
    public CustomerResponse byId(@PathVariable UUID id) {
        return toResponse(getCustomerProfile.handle(new GetCustomerProfileQuery(id)));
    }

    private static CustomerResponse toResponse(CustomerProfile profile) {
        List<CustomerResponse.Account> accounts =
                profile.accounts().stream().map(CustomerController::toAccount).toList();

        return new CustomerResponse(
                profile.id(),
                profile.externalRef(),
                profile.firstName(),
                profile.lastName(),
                profile.region(),
                profile.segment() == null ? null : profile.segment().name(),
                profile.tenureMonths(),
                accounts);
    }

    private static CustomerResponse.Account toAccount(CustomerProfile.Account account) {
        CustomerProfile.Product product = account.product();
        return new CustomerResponse.Account(
                account.id(),
                IbanMask.mask(account.iban()),
                account.currency(),
                account.balance(),
                account.overdraftLimit(),
                account.status() == null ? null : account.status().name(),
                account.openedAt(),
                account.closedAt(),
                product == null
                        ? null
                        : new CustomerResponse.Product(
                                product.code(),
                                product.name(),
                                product.category() == null ? null : product.category().name()),
                account.cards().stream()
                        .map(card -> new CustomerResponse.Card(
                                card.id(),
                                card.panLast4(),
                                card.network().name(),
                                card.type().name(),
                                card.status().name(),
                                card.expiresOn()))
                        .toList());
    }
}
