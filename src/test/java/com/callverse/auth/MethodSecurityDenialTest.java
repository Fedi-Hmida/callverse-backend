package com.callverse.auth;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.callverse.core.domain.enums.UserRole;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A {@code @PreAuthorize} denial must be a 403 (or a 401 for an anonymous caller), never a 500.
 *
 * <p><strong>The trap this pins.</strong> Method security throws {@code AccessDeniedException} from
 * an interceptor <em>inside</em> the dispatcher, so the security chain's denied handler never sees
 * it. {@code GlobalExceptionHandler}'s catch-all {@code @ExceptionHandler(Exception.class)} does —
 * and without a more specific handler it turns every routine permission denial into a 500 with an
 * ERROR-level stack trace.
 *
 * <p><strong>Why a test-only endpoint.</strong> No shipped endpoint carries {@code @PreAuthorize}
 * yet; that is sub-phase 2.5. The probe below is nested in this test class, which excludes it from
 * component scanning, and is brought in with {@code @Import} for this context only. Once 2.5 lands
 * a real guarded endpoint, point these assertions at it and delete the probe.
 *
 * <p>Runs under {@code dev} on purpose: the dev chain permits every URL, so any denial observed here
 * can only have come from method security, not from a URL rule.
 */
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(MethodSecurityDenialTest.AdminOnlyProbe.class)
class MethodSecurityDenialTest extends AbstractPersistenceTest {

    private static final String PROBE = "/test-only/admin-probe";

    @RestController
    static class AdminOnlyProbe {
        @GetMapping(PROBE)
        @PreAuthorize("hasRole('ADMIN')")
        String adminOnly() {
            return "ok";
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("an authenticated caller without the role gets 403 ACCESS_DENIED in the envelope, not 500")
    void wrongRoleIsForbidden() throws Exception {
        MvcResult result = perform(token(UserRole.CUSTOMER));

        ErrorEnvelope.assertConforms(body(result), 403, "ACCESS_DENIED");
    }

    @Test
    @DisplayName("an anonymous caller gets 401 UNAUTHENTICATED, not 403 and not 500")
    void anonymousIsUnauthenticated() throws Exception {
        MvcResult result = mockMvc.perform(get(PROBE)).andReturn();

        ErrorEnvelope.assertConforms(body(result), 401, "UNAUTHENTICATED");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("the role authority follows Spring's ROLE_ convention, so hasRole('ADMIN') admits an ADMIN token")
    void rightRoleIsAdmitted() throws Exception {
        MvcResult result = perform(token(UserRole.ADMIN));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("ok");
    }

    @Test
    @DisplayName("MVC's 403 has exactly the shape the security chain's 403 has")
    void mvcDenialMatchesTheChainShape() throws Exception {
        JsonNode body = body(perform(token(UserRole.ADVISOR)));

        assertThat(ErrorEnvelope.shapeOf(body))
                .containsExactly(
                        "timestamp:STRING", "status:NUMBER", "code:STRING", "message:STRING", "path:STRING");
        assertThat(body.get("message").asText())
                .isEqualTo("You are not permitted to perform this operation.");
    }

    private static String token(UserRole role) {
        return AuthenticatedRequests.validToken(UUID.randomUUID(), role.name().toLowerCase() + "@callverse.test", role);
    }

    private MvcResult perform(String token) throws Exception {
        return mockMvc.perform(get(PROBE).with(bearer(token))).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
