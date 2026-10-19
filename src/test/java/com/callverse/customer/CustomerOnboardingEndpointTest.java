package com.callverse.customer;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.callverse.core.domain.enums.UserRole;
import com.callverse.core.domain.services.FrenchIban;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Opening a customer at the bank: staff create the login, the profile, a current account and a
 * debit card in one step, and the customer can sign in and see them straight away.
 */
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class CustomerOnboardingEndpointTest extends AbstractPersistenceTest {

    private static final String PATH = "/api/v1/customers";
    private static final String PASSWORD = "Bienvenue-2026!";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private static RequestPostProcessor as(UserRole role) {
        return bearer(validToken(UUID.randomUUID(), role.name().toLowerCase() + "@test.local", role));
    }

    private static Map<String, Object> request(String email) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("firstName", "Nadia");
        body.put("lastName", "Benali");
        body.put("phone", "+33 6 12 34 56 78");
        body.put("region", "Lyon");
        body.put("segment", "AFFLUENT");
        body.put("productCode", "CUR_PREMIUM");
        body.put("initialDeposit", 250.0);
        body.put("temporaryPassword", PASSWORD);
        return body;
    }

    private MvcResult send(Map<String, Object> body, RequestPostProcessor who) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)).with(who))
                .andReturn();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String email() {
        return "nadia-" + UUID.randomUUID().toString().substring(0, 8) + "@client.test";
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"ADVISOR", "SUPERVISOR", "ADMIN"})
    @DisplayName("an advisor, a supervisor or an admin opens a complete customer")
    void staffOpenACompleteCustomer(UserRole role) throws Exception {
        String email = email();
        MvcResult result = send(request(email), as(role));

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode created = read(result);
        assertThat(created.get("email").asText()).isEqualTo(email);
        assertThat(created.get("externalRef").asText()).matches("CV-\\d{8}");
        assertThat(created.get("maskedIban").asText()).startsWith("FR76").contains("*");
        assertThat(created.get("cardLast4").asText()).matches("\\d{4}");

        UUID customerId = UUID.fromString(created.get("customerId").asText());
        Map<String, Object> row = jdbc.queryForMap("""
                select c.segment, c.region, c.phone, u.role, u.active, a.iban, a.balance, p.code as product,
                       (select count(*) from card k where k.account_id = a.id) as cards,
                       (select count(*) from bank_transaction t where t.account_id = a.id) as movements
                  from customer c join app_user u on u.id = c.user_id
                  join account a on a.customer_id = c.id join banking_product p on p.id = a.product_id
                 where c.id = ?""", customerId);
        assertThat(row).containsEntry("segment", "AFFLUENT").containsEntry("region", "Lyon")
                .containsEntry("role", "CUSTOMER").containsEntry("active", true).containsEntry("product", "CUR_PREMIUM");
        assertThat(FrenchIban.isValid((String) row.get("iban"))).isTrue();
        assertThat(((Number) row.get("balance")).doubleValue()).isEqualTo(250.0);
        assertThat(((Number) row.get("cards")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("movements")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("the new customer signs in with the temporary password and sees the account")
    void theCustomerCanSignInAndSeeTheAccount() throws Exception {
        String email = email();
        assertThat(send(request(email), as(UserRole.ADVISOR)).getResponse().getStatus()).isEqualTo(201);

        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD)))).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        String token = read(login).get("token").asText();

        JsonNode me = read(mvc.perform(get("/api/v1/me/customer").header("Authorization", "Bearer " + token)).andReturn());
        assertThat(me.get("firstName").asText()).isEqualTo("Nadia");
        assertThat(me.get("accounts")).hasSize(1);
        assertThat(me.get("accounts").get(0).get("cards")).hasSize(1);
    }

    @Test
    @DisplayName("without an opening deposit, the account starts at zero with no movement")
    void noDepositMeansNoMovement() throws Exception {
        Map<String, Object> body = request(email());
        body.remove("initialDeposit");
        body.put("productCode", "CUR_ESSENTIAL");
        JsonNode created = read(send(body, as(UserRole.ADVISOR)));
        Number movements = jdbc.queryForObject("""
                select count(*) from bank_transaction t join account a on a.id = t.account_id where a.customer_id = ?""",
                Number.class, UUID.fromString(created.get("customerId").asText()));
        assertThat(movements.intValue()).isZero();
    }

    @Test
    @DisplayName("a customer or an anonymous caller cannot open customers")
    void customersAndAnonymousAreRefused() throws Exception {
        assertThat(send(request(email()), as(UserRole.CUSTOMER)).getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(email())))).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("a taken e-mail is a conflict, and nothing half-created is left behind")
    void duplicateEmailIsRefusedWholly() throws Exception {
        String email = email();
        assertThat(send(request(email), as(UserRole.ADMIN)).getResponse().getStatus()).isEqualTo(201);
        Number before = jdbc.queryForObject("select count(*) from customer", Number.class);

        MvcResult again = send(request(email), as(UserRole.ADMIN));

        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(again).get("code").asText()).isEqualTo("EMAIL_ALREADY_USED");
        assertThat(jdbc.queryForObject("select count(*) from customer", Number.class)).isEqualTo(before);
    }

    @Test
    @DisplayName("invalid input is refused before anything is written")
    void invalidInputIsRefused() throws Exception {
        Map<String, Object> weak = request(email());
        weak.put("temporaryPassword", "court");
        assertThat(read(send(weak, as(UserRole.ADVISOR))).get("code").asText()).isIn("PASSWORD_TOO_WEAK", "VALIDATION_FAILED");

        Map<String, Object> product = request(email());
        product.put("productCode", "MORTGAGE_20Y");
        assertThat(send(product, as(UserRole.ADVISOR)).getResponse().getStatus()).isEqualTo(400);

        Map<String, Object> segment = request(email());
        segment.put("segment", "VIP");
        assertThat(send(segment, as(UserRole.ADVISOR)).getResponse().getStatus()).isEqualTo(400);

        Map<String, Object> deposit = request(email());
        deposit.put("initialDeposit", -5);
        assertThat(send(deposit, as(UserRole.ADVISOR)).getResponse().getStatus()).isEqualTo(400);

        Map<String, Object> missing = request(email());
        missing.remove("lastName");
        assertThat(send(missing, as(UserRole.ADVISOR)).getResponse().getStatus()).isEqualTo(400);
    }
}
