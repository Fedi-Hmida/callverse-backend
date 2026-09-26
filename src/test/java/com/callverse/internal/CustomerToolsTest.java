package com.callverse.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.callverse.auth.ErrorEnvelope;
import com.callverse.core.domain.entities.Contract;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.entities.Invoice;
import com.callverse.core.domain.enums.InvoiceStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /internal/customers/{id}} and {@code GET /internal/customers/{id}/invoices} —
 * {@code OWNERSHIP_RULES.md} E1 and E2.
 *
 * <p>The two guarantees that matter: the agent never sees fields it could game or that leak the
 * experiment ({@code churn_risk}, {@code is_simulated}), and invoices are reached from the customer
 * — never from a caller-chosen contract — so another customer's bills cannot appear. The latter is
 * rule A4, ranked the most likely rule in the catalogue to be forgotten.
 */
class CustomerToolsTest extends AbstractInternalToolTest {

    @Test
    @DisplayName("the profile carries identity, zone, tenure and each contract with its plan")
    void profileCarriesContractsAndPlans() throws Exception {
        Customer customer = fixtures.customer("PARIS-15", false);
        Contract fiber = fixtures.contract(customer, "FIB_1G");

        JsonNode body = ok(get("/internal/customers/" + customer.getId()));

        assertThat(body.get("id").asText()).isEqualTo(customer.getId().toString());
        assertThat(body.get("externalRef").asText()).isEqualTo(customer.getExternalRef());
        assertThat(body.get("zone").asText()).isEqualTo("PARIS-15");
        assertThat(body.get("tenureMonths").asInt()).isEqualTo(26);
        JsonNode contract = body.get("contracts").get(0);
        assertThat(contract.get("id").asText()).isEqualTo(fiber.getId().toString());
        assertThat(contract.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(contract.get("plan").get("code").asText()).isEqualTo("FIB_1G");
        assertThat(contract.get("plan").get("monthlyPrice").decimalValue()).isEqualByComparingTo("44.99");
    }

    @Test
    @DisplayName("fields the agent could game or that leak the experiment never leave the backend")
    void sensitiveFieldsAreNotExposed() throws Exception {
        Customer customer = fixtures.customer("PARIS-15", true);

        JsonNode body = ok(get("/internal/customers/" + customer.getId()));

        assertThat(body.fieldNames())
                .toIterable()
                .doesNotContain("churnRisk", "simulated", "isSimulated", "userId", "user", "phone", "createdAt");
        assertThat(body.toString()).doesNotContain("HIGH");
    }

    @Test
    @DisplayName("an unknown customer is 404 RESOURCE_NOT_FOUND")
    void unknownCustomerIsNotFound() throws Exception {
        ErrorEnvelope.assertConforms(
                call(get("/internal/customers/" + UUID.randomUUID()), 404), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("invoices are the customer's own, across every contract, most recent first, 3 by default")
    void invoicesAreTheCustomersOwnMostRecentFirst() throws Exception {
        Customer customer = fixtures.customer("LYON-3", false);
        Contract mobile = fixtures.contract(customer, "MOB_ESSENTIAL");
        Contract fiber = fixtures.contract(customer, "FIB_1G");
        fixtures.invoice(mobile, LocalDate.of(2026, 6, 1), "19.99", InvoiceStatus.PAID);
        Invoice july = fixtures.invoice(fiber, LocalDate.of(2026, 7, 1), "44.99", InvoiceStatus.PAID);
        Invoice august = fixtures.invoice(mobile, LocalDate.of(2026, 8, 1), "19.99", InvoiceStatus.PAID);
        Invoice september = fixtures.invoice(fiber, LocalDate.of(2026, 9, 1), "52.40", InvoiceStatus.OVERDUE);

        JsonNode body = ok(get("/internal/customers/" + customer.getId() + "/invoices"));

        assertThat(ids(body.get("invoices")))
                .containsExactly(september.getId().toString(), august.getId().toString(), july.getId().toString());
        assertThat(body.get("invoices").get(0).get("status").asText()).isEqualTo("OVERDUE");
        assertThat(body.get("invoices").get(0).get("amount").decimalValue()).isEqualByComparingTo("52.40");
    }

    @Test
    @DisplayName("another customer's invoices never appear, even in the same zone on the same plan")
    void anotherCustomersInvoicesNeverAppear() throws Exception {
        Customer mine = fixtures.customer("LYON-3", false);
        Customer theirs = fixtures.customer("LYON-3", false);
        Invoice myInvoice = fixtures.invoice(fixtures.contract(mine, "FIB_1G"), LocalDate.of(2026, 5, 1), "44.99", InvoiceStatus.PAID);
        Invoice theirInvoice =
                fixtures.invoice(fixtures.contract(theirs, "FIB_1G"), LocalDate.of(2026, 9, 1), "44.99", InvoiceStatus.PENDING);

        JsonNode body = ok(get("/internal/customers/" + mine.getId() + "/invoices").param("n", "12"));

        assertThat(ids(body.get("invoices")))
                .containsExactly(myInvoice.getId().toString())
                .doesNotContain(theirInvoice.getId().toString());
    }

    @Test
    @DisplayName("a known customer with no invoices gets an empty list, not a 404")
    void noInvoicesIsAnEmptyList() throws Exception {
        Customer customer = fixtures.customer("LYON-3", false);

        assertThat(ok(get("/internal/customers/" + customer.getId() + "/invoices")).get("invoices")).isEmpty();
    }

    @Test
    @DisplayName("n is bounded to 1..12: 0 and 13 are 400 VALIDATION_FAILED")
    void invoiceCountIsBounded() throws Exception {
        Customer customer = fixtures.customer("LYON-3", false);
        String path = "/internal/customers/" + customer.getId() + "/invoices";

        ErrorEnvelope.assertConforms(call(get(path).param("n", "13"), 400), 400, "VALIDATION_FAILED");
        ErrorEnvelope.assertConforms(call(get(path).param("n", "0"), 400), 400, "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("invoices of an unknown customer are 404, not an empty list that hides a wrong id")
    void invoicesOfUnknownCustomerAreNotFound() throws Exception {
        ErrorEnvelope.assertConforms(
                call(get("/internal/customers/" + UUID.randomUUID() + "/invoices"), 404), 404, "RESOURCE_NOT_FOUND");
    }
}
