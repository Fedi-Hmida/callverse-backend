package com.callverse.host.api.controllers;

import com.callverse.core.application.features.customer.queries.GetCustomerProfileQuery;
import com.callverse.core.application.features.customer.queries.GetCustomerProfileQueryHandler;
import com.callverse.core.application.interfaces.CustomerProfile;
import com.callverse.host.api.dto.response.CustomerResponse;
import com.callverse.host.api.dto.response.IbanMask;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer records, for staff.
 *
 * <p><strong>The first shipped endpoint in this project to carry a role gate.</strong> Until now
 * authorisation existed only as a catalogue of rules and a test-only probe; this is the first place a
 * real request is refused because of who is asking.
 *
 * <p><strong>Two layers decide access, and both are necessary.</strong> The filter chain decides
 * reachability — under any profile other than {@code dev} the default chain is deny-by-default, so
 * this route carries an explicit {@code authenticated()} rule in {@code SecurityConfiguration}.
 * Method security then decides authorisation. Removing either one produces the same 403, which is
 * why the test asserts a successful ADMIN read as well as a refused ADVISOR one.
 *
 * <p><strong>ADMIN-only, and that is a deliberate stopping point rather than a finished design.</strong>
 * A role gate needs no ownership predicate, which is what makes it buildable today: the
 * {@code app_user → customer} hop that every ownership rule begins with has no query behind it, and
 * cannot have a well-defined one until {@code customer.user_id} carries a {@code UNIQUE} constraint.
 * Until then "the customer whose {@code user_id} is mine" is not a well-defined phrase, so there is
 * no CUSTOMER path here. Sub-phase 2.5 adds one, and adds ADVISOR access scoped to a live
 * conversation. <strong>Do not widen the annotation to include those roles without the predicates
 * that go with them</strong> — {@code hasRole('ADVISOR')} alone on this route is a full
 * customer-database read for every advisor.
 */
@RestController
// produces is pinned so the published contract says application/json rather than the */*
// springdoc infers when a controller stays silent.
@RequestMapping(path = "/api/v1/customers", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Customers", description = "Customer records, readable by staff")
public class CustomerController {

    private final GetCustomerProfileQueryHandler getCustomerProfile;

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Read a customer",
            description =
                    "Returns the customer, their accounts and the products they hold. ADMIN only "
                            + "for now: the CUSTOMER and ADVISOR paths need ownership predicates that "
                            + "are not yet expressible. IBANs are masked; churn risk is deliberately "
                            + "not exposed. An unknown id is 404 RESOURCE_NOT_FOUND; a non-ADMIN "
                            + "caller is 403 ACCESS_DENIED.")
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
                                product.category() == null ? null : product.category().name()));
    }
}
