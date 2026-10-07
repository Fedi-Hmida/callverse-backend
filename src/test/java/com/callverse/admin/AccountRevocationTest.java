package com.callverse.admin;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.tokenForUnknownAccount;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.callverse.auth.ErrorEnvelope;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A token is only as good as the account behind it. Every authenticated request re-reads the
 * account: unknown, blocked, or holding another role than the token says — refused with 401, on the
 * very next request, without waiting for the token to expire.
 */
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class AccountRevocationTest extends AbstractPersistenceTest {

    private static final String ME = "/api/v1/auth/me";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private JsonNode me(String token, int expected) throws Exception {
        MvcResult r = mockMvc.perform(get(ME).with(bearer(token))).andReturn();
        String body = r.getResponse().getContentAsString();
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(expected);
        return json.readTree(body);
    }

    @Test
    @DisplayName("a token for an account that does not exist is refused: 401 UNAUTHENTICATED")
    void unknownAccount() throws Exception {
        ErrorEnvelope.assertConforms(me(tokenForUnknownAccount(UserRole.ADMIN), 401), 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("blocking an account refuses its existing token on the next request; unblocking restores it")
    void blockedAccount() throws Exception {
        UUID karim = UUID.randomUUID();
        String token = validToken(karim, "karim@bank.test", UserRole.ADVISOR);
        me(token, 200);

        jdbc.update("update app_user set active = false where id = ?", karim);
        ErrorEnvelope.assertConforms(me(token, 401), 401, "UNAUTHENTICATED");

        jdbc.update("update app_user set active = true where id = ?", karim);
        me(token, 200);
    }

    @Test
    @DisplayName("a role change refuses the token that still claims the old role: log in again to get the new one")
    void roleChanged() throws Exception {
        UUID lina = UUID.randomUUID();
        String asAdvisor = validToken(lina, "lina@bank.test", UserRole.ADVISOR);
        me(asAdvisor, 200);

        jdbc.update("update app_user set role = 'SUPERVISOR' where id = ?", lina);

        ErrorEnvelope.assertConforms(me(asAdvisor, 401), 401, "UNAUTHENTICATED");
        assertThat(me(validToken(lina, "lina@bank.test", UserRole.SUPERVISOR), 200).get("role").asText())
                .isEqualTo("SUPERVISOR");
    }

    @Test
    @DisplayName("an ADVISOR promoted to ADMIN cannot use the old token to reach admin routes, nor the new role early")
    void noEscalationThroughStaleToken() throws Exception {
        UUID omar = UUID.randomUUID();
        String asAdvisor = validToken(omar, "omar@bank.test", UserRole.ADVISOR);
        jdbc.update("update app_user set role = 'ADMIN' where id = ?", omar);

        MvcResult r = mockMvc.perform(get("/api/v1/customers/{id}", UUID.randomUUID()).with(bearer(asAdvisor))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }
}
