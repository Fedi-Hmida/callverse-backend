package com.callverse.admin;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.validToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.callverse.auth.ErrorEnvelope;
import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Account administration over HTTP, against the deny-by-default {@code prod} chain: an ADMIN gives,
 * changes and withdraws access, and every change is felt by the target on their next request.
 */
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class AdminUserEndpointTest extends AbstractPersistenceTest {

    private static final String USERS = "/api/v1/admin/users";
    private static final String PASSWORD = "plateau-lundi-9h30";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;

    private UUID adminId;
    private RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        admin = bearer(validToken(adminId, "nadia@bank.test", UserRole.ADMIN));
    }

    // ------------------------------------------------------------------ plumbing

    private JsonNode call(RequestBuilder request, int expected) throws Exception {
        MvcResult r = mockMvc.perform(request).andReturn();
        String body = r.getResponse().getContentAsString();
        assertThat(r.getResponse().getStatus()).as("body was: %s", body).isEqualTo(expected);
        return body.isEmpty() ? json.nullNode() : json.readTree(body);
    }

    private RequestBuilder create(String email, UserRole role, String password, RequestPostProcessor who)
            throws Exception {
        return post(USERS).contentType(MediaType.APPLICATION_JSON).with(who).content(json.writeValueAsString(Map.of(
                "email", email, "firstName", "Karim", "lastName", "Benali", "role", role.name(), "password", password)));
    }

    private static String email() {
        return "karim-" + UUID.randomUUID().toString().substring(0, 8) + "@bank.test";
    }

    private JsonNode createdAdvisor() throws Exception {
        return call(create(email(), UserRole.ADVISOR, PASSWORD, admin), 201);
    }

    private int login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andReturn().getResponse().getStatus();
    }

    private String tokenOf(String email, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private RequestBuilder me(String token) {
        return get("/api/v1/auth/me").with(bearer(token));
    }

    private RequestBuilder role(Object id, String role, RequestPostProcessor who) {
        return put(USERS + "/{id}/role", id).contentType(MediaType.APPLICATION_JSON).with(who)
                .content("{\"role\": \"" + role + "\"}");
    }

    // ------------------------------------------------------------------ creating access

    @Nested
    @DisplayName("POST /api/v1/admin/users")
    class Create {

        @Test
        @DisplayName("an ADMIN creates an account; it can log in at once; no password or hash is ever returned")
        void creates() throws Exception {
            String email = email();
            MvcResult r = mockMvc.perform(create(email.toUpperCase(), UserRole.ADVISOR, PASSWORD, admin)).andReturn();
            String raw = r.getResponse().getContentAsString();
            assertThat(r.getResponse().getStatus()).as(raw).isEqualTo(201);
            assertThat(r.getResponse().getHeader("Location")).startsWith(USERS + "/");

            JsonNode body = json.readTree(raw);
            assertThat(body.get("email").asText()).as("stored in lower case").isEqualTo(email);
            assertThat(body.get("role").asText()).isEqualTo("ADVISOR");
            assertThat(body.get("active").asBoolean()).isTrue();
            assertThat(raw).doesNotContain(PASSWORD).doesNotContain("$2a$").doesNotContainIgnoringCase("password");

            assertThat(json.readTree(mockMvc.perform(me(tokenOf(email, PASSWORD))).andReturn().getResponse()
                    .getContentAsString()).get("role").asText()).isEqualTo("ADVISOR");
        }

        @Test
        @DisplayName("an address already used, in any case, is 409 EMAIL_ALREADY_USED")
        void duplicate() throws Exception {
            String email = email();
            call(create(email, UserRole.ADVISOR, PASSWORD, admin), 201);
            ErrorEnvelope.assertConforms(call(create(email.toUpperCase(), UserRole.CUSTOMER, PASSWORD, admin), 409),
                    409, "EMAIL_ALREADY_USED");
        }

        @Test
        @DisplayName("a weak password, a bad email or a missing role is 400, and the password is never echoed")
        void validation() throws Exception {
            JsonNode weak = call(create(email(), UserRole.ADVISOR, "short-pw", admin), 400);
            ErrorEnvelope.assertConforms(weak, 400, "VALIDATION_FAILED");
            assertThat(weak.toString()).doesNotContain("short-pw");
            call(create(email(), UserRole.ADVISOR, "x".repeat(80), admin), 400);
            call(create("not-an-email", UserRole.ADVISOR, PASSWORD, admin), 400);
            call(create("kärim@bank.test", UserRole.ADVISOR, PASSWORD, admin), 400);   // ASCII addresses only
            call(post(USERS).contentType(MediaType.APPLICATION_JSON).with(admin).content(
                    "{\"email\": \"a@bank.test\", \"firstName\": \"A\", \"lastName\": \"B\", \"password\": \"" + PASSWORD + "\"}"), 400);
        }
    }

    // ------------------------------------------------------------------ withdrawing access

    @Nested
    @DisplayName("block and unblock")
    class Block {

        @Test
        @DisplayName("blocking refuses the user's token on the next request and their login; unblocking gives both back")
        void blockAndUnblock() throws Exception {
            String email = email();
            JsonNode karim = call(create(email, UserRole.ADVISOR, PASSWORD, admin), 201);
            String token = tokenOf(email, PASSWORD);
            call(me(token), 200);

            JsonNode blocked = call(post(USERS + "/{id}/block", karim.get("id").asText()).with(admin), 200);
            assertThat(blocked.get("active").asBoolean()).isFalse();
            ErrorEnvelope.assertConforms(call(me(token), 401), 401, "UNAUTHENTICATED");
            assertThat(login(email, PASSWORD)).isEqualTo(401);

            call(post(USERS + "/{id}/block", karim.get("id").asText()).with(admin), 200);   // idempotent

            assertThat(call(post(USERS + "/{id}/unblock", karim.get("id").asText()).with(admin), 200)
                    .get("active").asBoolean()).isTrue();
            assertThat(login(email, PASSWORD)).isEqualTo(200);
        }

        @Test
        @DisplayName("an admin cannot block themselves or change their own role: 409 SELF_LOCKOUT")
        void selfLockout() throws Exception {
            ErrorEnvelope.assertConforms(call(post(USERS + "/{id}/block", adminId).with(admin), 409), 409, "SELF_LOCKOUT");
            call(role(adminId, "CUSTOMER", admin), 409);
            call(get("/api/v1/auth/me").with(admin), 200);
        }

        @Test
        @DisplayName("a blocked admin's token cannot reach the admin routes any more")
        void blockedAdmin() throws Exception {
            UUID omarId = UUID.randomUUID();
            RequestPostProcessor omar = bearer(validToken(omarId, "omar@bank.test", UserRole.ADMIN));
            call(get(USERS).with(omar), 200);

            call(post(USERS + "/{id}/block", omarId).with(admin), 200);

            call(get(USERS).with(omar), 401);
        }

        @Test
        @DisplayName("an unknown account is 404")
        void unknown() throws Exception {
            call(post(USERS + "/{id}/block", UUID.randomUUID()).with(admin), 404);
            call(get(USERS + "/{id}", UUID.randomUUID()).with(admin), 404);
        }
    }

    // ------------------------------------------------------------------ changing access

    @Nested
    @DisplayName("PUT /api/v1/admin/users/{id}/role")
    class Role {

        @Test
        @DisplayName("a role change refuses the token with the old role; the next login carries the new one")
        void changes() throws Exception {
            String email = email();
            JsonNode lina = call(create(email, UserRole.ADVISOR, PASSWORD, admin), 201);
            String asAdvisor = tokenOf(email, PASSWORD);

            assertThat(call(role(lina.get("id").asText(), "SUPERVISOR", admin), 200).get("role").asText())
                    .isEqualTo("SUPERVISOR");

            call(me(asAdvisor), 401);
            assertThat(json.readTree(mockMvc.perform(me(tokenOf(email, PASSWORD))).andReturn().getResponse()
                    .getContentAsString()).get("role").asText()).isEqualTo("SUPERVISOR");
            call(role(lina.get("id").asText(), "SUPERVISOR", admin), 200);   // idempotent
        }

        @Test
        @DisplayName("an unknown role is 400")
        void badRole() throws Exception {
            call(role(createdAdvisor().get("id").asText(), "ROOT", admin), 400);
        }
    }

    // ------------------------------------------------------------------ reading

    @Nested
    @DisplayName("GET /api/v1/admin/users")
    class Read {

        @Test
        @DisplayName("paged, filtered by role, status and text; never a hash")
        void list() throws Exception {
            String tag = "zt" + UUID.randomUUID().toString().substring(0, 6);
            for (int i = 0; i < 3; i++) {
                call(create(tag + i + "@bank.test", UserRole.SUPERVISOR, PASSWORD, admin), 201);
            }

            MvcResult r = mockMvc.perform(get(USERS).param("q", tag).param("size", "2").with(admin)).andReturn();
            String raw = r.getResponse().getContentAsString();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            JsonNode page = json.readTree(raw);
            assertThat(page.get("content")).hasSize(2);
            assertThat(page.get("page").get("totalElements").asLong()).isEqualTo(3);
            assertThat(page.get("page").get("totalPages").asInt()).isEqualTo(2);
            assertThat(page.get("page").get("number").asInt()).isZero();
            assertThat(raw).doesNotContain("$2a$").doesNotContainIgnoringCase("passwordHash");

            assertThat(call(get(USERS).param("q", tag).param("role", "ADVISOR").with(admin), 200)
                    .get("page").get("totalElements").asLong()).isZero();
            assertThat(call(get(USERS).param("q", tag).param("active", "true").with(admin), 200)
                    .get("page").get("totalElements").asLong()).isEqualTo(3);
        }

        @Test
        @DisplayName("size is 1 to 100 and page is not negative: otherwise 400")
        void bounds() throws Exception {
            call(get(USERS).param("size", "100").with(admin), 200);
            ErrorEnvelope.assertConforms(call(get(USERS).param("size", "101").with(admin), 400), 400, "VALIDATION_FAILED");
            call(get(USERS).param("size", "0").with(admin), 400);
            call(get(USERS).param("page", "-1").with(admin), 400);
        }

        @Test
        @DisplayName("one account by id")
        void one() throws Exception {
            JsonNode created = createdAdvisor();
            assertThat(call(get(USERS + "/{id}", created.get("id").asText()).with(admin), 200).get("email").asText())
                    .isEqualTo(created.get("email").asText());
        }
    }

    // ------------------------------------------------------------------ who may

    @Test
    @DisplayName("only an ADMIN: supervisors, advisors and customers get 403 on every route; anonymous 401")
    void onlyAdmins() throws Exception {
        String id = createdAdvisor().get("id").asText();
        for (UserRole role : new UserRole[] {UserRole.SUPERVISOR, UserRole.ADVISOR, UserRole.CUSTOMER}) {
            RequestPostProcessor who = bearer(validToken(UUID.randomUUID(), "x@bank.test", role));
            call(get(USERS).with(who), 403);
            call(get(USERS + "/{id}", id).with(who), 403);
            call(create(email(), UserRole.ADMIN, PASSWORD, who), 403);
            call(role(id, "ADMIN", who), 403);
            call(post(USERS + "/{id}/block", id).with(who), 403);
            call(post(USERS + "/{id}/unblock", id).with(who), 403);
        }
        call(get(USERS), 401);
        call(post(USERS + "/{id}/block", id), 401);
    }
}
