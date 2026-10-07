package com.callverse.customer;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.callverse.core.domain.entities.Account;
import com.callverse.core.domain.entities.BankingProduct;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.enums.AccountStatus;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@code GET /api/v1/customers/{id}} — the first role-gated endpoint in the project.
 *
 * <p><strong>Why this test runs under {@code prod} and not {@code dev}.</strong> Every other
 * endpoint test in this repository activates {@code dev}, where the filter chain is
 * {@code anyRequest().permitAll()}. Under that chain a 403-versus-200 distinction proves almost
 * nothing about the chain itself — only that method security fired. The non-dev chain is
 * deny-by-default, so this is the only profile where "staff may, a CUSTOMER may not" is a
 * statement about the deployed system rather than about an annotation in isolation.
 *
 * <p><strong>Two layers have to agree for this endpoint to work, and the test covers both.</strong>
 * The filter chain decides reachability — it must permit an authenticated caller to reach the route
 * at all. Method security then decides authorisation. Get either wrong and the symptom is the same
 * 403, which is why a passing ADMIN case matters as much as a refused ADVISOR one.
 *
 * <p>This closes two Phase-1 exit criteria the kickoff briefs recorded: that this route returns a
 * real customer from the database, and that a protected endpoint refuses an unauthorised role under
 * an automated test.
 */
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class CustomerEndpointTest extends AbstractPersistenceTest {

    private static final String PATH = "/api/v1/customers/{id}";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager em;

    @Test
    @Transactional
    @DisplayName("an ADMIN reads a real customer and their accounts out of the database")
    void adminReadsCustomer() throws Exception {
        Customer stored = persistCustomer("CUST-ADMIN-READ", "Marseille");
        stored.setSegment(CustomerSegment.AFFLUENT);
        persistAccount(stored, RAW_IBAN);
        em.flush();
        em.clear();

        MvcResult result = mockMvc.perform(get(PATH, stored.getId()).with(admin())).andReturn();
        String raw = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as("body was: %s", raw).isEqualTo(200);
        JsonNode body = objectMapper.readTree(raw);

        assertThat(body.get("id").asText()).isEqualTo(stored.getId().toString());
        assertThat(body.get("externalRef").asText()).isEqualTo("CUST-ADMIN-READ");
        assertThat(body.get("region").asText()).isEqualTo("Marseille");
        assertThat(body.get("segment").asText()).isEqualTo("AFFLUENT");
        JsonNode account = body.get("accounts").get(0);
        assertThat(account.get("maskedIban").asText()).isEqualTo("FR76 **** **** 0189");
        assertThat(account.get("balance").decimalValue()).isEqualByComparingTo("1523.40");
        assertThat(account.get("product").get("category").asText()).isEqualTo("CURRENT_ACCOUNT");
        assertThat(raw)
                .as("the full IBAN must never leave this route")
                .doesNotContain(RAW_IBAN)
                .doesNotContain("300060000112345678");
        assertThat(body.has("churnRisk"))
                .as("churn_risk feeds priority_score; it must not leave the backend")
                .isFalse();
    }

    @Test
    @Transactional
    @DisplayName("an ADVISOR reads a customer: the advisor console needs it before ownership rules exist")
    void advisorReadsCustomer() throws Exception {
        Customer stored = persistCustomer("CUST-ADVISOR-READ", "Lyon");

        JsonNode body = request(stored.getId(), advisor(), 200);

        assertThat(body.get("id").asText()).isEqualTo(stored.getId().toString());
    }

    @Test
    @Transactional
    @DisplayName("a CUSTOMER is refused with 403 in the frozen envelope")
    void customerIsRefused() throws Exception {
        Customer stored = persistCustomer("CUST-CUSTOMER-DENIED", "Lyon");

        JsonNode body = request(stored.getId(), customer(), 403);

        assertThat(body.fieldNames())
                .toIterable()
                .as("a denial must carry the same five fields as every other failure")
                .containsExactlyInAnyOrder("timestamp", "status", "code", "message", "path");
        assertThat(body.get("code").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(body.get("status").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("no token is refused with 401, not 403")
    void anonymousIsRefused() throws Exception {
        JsonNode body = request(UUID.randomUUID(), noCredentials(), 401);

        assertThat(body.get("code").asText())
                .as("the frontend's single-refresh rule fires on 401 and cannot fire on 403")
                .isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("an unknown customer is a 404 in the envelope, not an empty 200")
    void unknownCustomerIsNotFound() throws Exception {
        JsonNode body = request(UUID.randomUUID(), admin(), 404);

        assertThat(body.get("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    /** The documentation-example IBAN format: not a real account. */
    private static final String RAW_IBAN = "FR7630006000011234567890189";

    private void persistAccount(Customer customer, String iban) {
        BankingProduct product = em.createQuery(
                        "select p from BankingProduct p where p.code = 'CUR_ESSENTIAL'", BankingProduct.class)
                .getSingleResult();
        Account account = new Account();
        account.setCustomer(customer);
        account.setProduct(product);
        account.setIban(iban);
        account.setBalance(new BigDecimal("1523.40"));
        account.setStatus(AccountStatus.ACTIVE);
        account.setOpenedAt(LocalDate.of(2024, 3, 1));
        em.persist(account);
    }

    private Customer persistCustomer(String externalRef, String region) {
        Customer customer = new Customer();
        customer.setExternalRef(externalRef);
        customer.setFirstName("Test");
        customer.setLastName("Customer");
        customer.setRegion(region);
        customer.setTenureMonths(18);
        em.persist(customer);
        em.flush();
        return customer;
    }

    private static RequestPostProcessor admin() {
        return bearer(validToken(UUID.randomUUID(), "admin@callverse.local", UserRole.ADMIN));
    }

    private static RequestPostProcessor advisor() {
        return bearer(validToken(UUID.randomUUID(), "advisor@callverse.local", UserRole.ADVISOR));
    }

    private static RequestPostProcessor customer() {
        return bearer(validToken(UUID.randomUUID(), "customer@callverse.local", UserRole.CUSTOMER));
    }

    /** A no-op post-processor, so every case goes through the same request builder. */
    private static RequestPostProcessor noCredentials() {
        return request -> request;
    }

    private JsonNode request(UUID id, RequestPostProcessor credentials, int expectedStatus)
            throws Exception {
        MvcResult result = mockMvc.perform(get(PATH, id).with(credentials)).andReturn();

        assertThat(result.getResponse().getStatus())
                .as("status for %s -> body was: %s", id, result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);

        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
