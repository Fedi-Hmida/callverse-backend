package com.callverse.host.api.controllers;

import com.callverse.core.application.features.customer.queries.GetCustomerProfileQuery;
import com.callverse.core.application.features.customer.queries.GetCustomerProfileQueryHandler;
import com.callverse.core.application.features.customer.queries.GetRecentInvoicesQuery;
import com.callverse.core.application.features.customer.queries.GetRecentInvoicesQueryHandler;
import com.callverse.host.api.dto.response.internal.CustomerProfileResponse;
import com.callverse.host.api.dto.response.internal.InvoicesResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The agent tools: the routes the Python AI service calls to read customer data and to act.
 *
 * <p><strong>For the AI lot.</strong> Base URL: the backend ({@code http://backend:8080} on the
 * compose network). Every call sends {@code X-Internal-Key} with the shared key from
 * {@code INTERNAL_SERVICE_KEY}; without it, with a wrong one, or with a user's JWT, the answer is
 * 401 {@code UNAUTHENTICATED}. Errors use the standard envelope — branch on {@code code}:
 * {@code RESOURCE_NOT_FOUND} (404), {@code VALIDATION_FAILED} and {@code MALFORMED_REQUEST} (400),
 * and the business codes documented per tool. <strong>Response shapes are provisional</strong>: the
 * project context fixes the routes, not the fields, so they are frozen once the agents consume them.
 *
 * <p><strong>No business rule lives here.</strong> Each method translates HTTP into a query or
 * command and back. Bounds, lookups and refusals are in the handlers, which are the authority.
 *
 * <p>Authentication is not declared here either: {@code InternalApiSecurityConfiguration} claims
 * {@code /internal/**} in every profile. {@code @SecurityRequirement} only tells the published
 * contract, and Swagger UI's Authorize button, which scheme to send.
 */
@RestController
@RequestMapping(path = "/internal", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@SecurityRequirement(name = "serviceKey")
@Tag(
        name = "Internal tools (AI service)",
        description =
                "Called by the Python AI service only, with the X-Internal-Key service key. Not a"
                        + " user API. Response shapes are provisional until agreed with the AI lot.")
public class InternalToolController {

    private final GetCustomerProfileQueryHandler customerProfile;
    private final GetRecentInvoicesQueryHandler recentInvoices;

    @GetMapping("/customers/{id}")
    @Operation(
            summary = "Customer profile and contracts",
            description =
                    "Identity, zone, tenure and every contract with its plan. Deliberately excludes"
                            + " churn risk, simulation flags and contact details. Unknown id: 404"
                            + " RESOURCE_NOT_FOUND.")
    public CustomerProfileResponse customer(@PathVariable UUID id) {
        return CustomerProfileResponse.from(customerProfile.handle(new GetCustomerProfileQuery(id)));
    }

    @GetMapping("/customers/{id}/invoices")
    @Operation(
            summary = "Recent invoices",
            description =
                    "The customer's most recent invoices across all contracts, newest period first."
                            + " Unknown customer: 404. n outside 1..12: 400 VALIDATION_FAILED.")
    public InvoicesResponse invoices(
            @PathVariable UUID id,
            @Parameter(description = "How many, 1 to 12; default 3") @RequestParam(name = "n", required = false)
                    Integer n) {
        return InvoicesResponse.from(id, recentInvoices.handle(new GetRecentInvoicesQuery(id, n)));
    }
}
