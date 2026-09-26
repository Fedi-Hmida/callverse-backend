package com.callverse.internal;

import static com.callverse.auth.AuthenticatedRequests.bearer;
import static com.callverse.auth.AuthenticatedRequests.serviceKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import com.callverse.auth.AuthenticatedRequests;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The service-key scheme on {@code /internal/**}, asserted identically under two profiles.
 *
 * <p><strong>Why the same tests run twice.</strong> The user chains are profile-bound: {@code dev}
 * permits every request, everything else denies by default. {@code /internal} must behave the same
 * under both, and {@code dev} is where it would silently fail — a route that "works" under
 * {@code permitAll} gives no sign that nothing is checking the key. The two subclasses differ only
 * in {@code @ActiveProfiles}.
 *
 * <p>The probe is test-only, so the scheme is proven independently of any real tool. It is nested
 * in this test hierarchy, which keeps it out of component scanning, and imported explicitly.
 */
@AutoConfigureMockMvc
@Import(AbstractInternalApiSecurityTest.InternalProbe.class)
abstract class AbstractInternalApiSecurityTest extends AbstractPersistenceTest {

    static final String PROBE = "/internal/test-only/probe";

    @RestController
    static class InternalProbe {
        @GetMapping(PROBE)
        String probe() {
            return "ok";
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("the right key is admitted")
    void rightKeyIsAdmitted() throws Exception {
        MvcResult result = mockMvc.perform(get(PROBE).with(serviceKey())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("ok");
    }

    @Test
    @DisplayName("no key is 401 UNAUTHENTICATED in the envelope, with no Bearer challenge")
    void noKeyIsUnauthenticated() throws Exception {
        MvcResult result = mockMvc.perform(get(PROBE)).andReturn();

        ErrorEnvelope.assertConforms(body(result), 401, "UNAUTHENTICATED");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .as("/internal does not accept Bearer tokens, so it must not invite one")
                .isNull();
    }

    @Test
    @DisplayName("a wrong key, a key one byte off and an empty key are refused exactly like no key")
    void wrongKeysAreIndistinguishableFromNoKey() throws Exception {
        JsonNode none = body(mockMvc.perform(get(PROBE)).andReturn());
        String oneByteOff = TEST_INTERNAL_SERVICE_KEY.substring(0, TEST_INTERNAL_SERVICE_KEY.length() - 1) + "X";

        for (String key : new String[] {"wrong", oneByteOff, TEST_INTERNAL_SERVICE_KEY + "-longer", ""}) {
            MvcResult result = mockMvc.perform(get(PROBE).with(serviceKey(key))).andReturn();
            JsonNode refused = body(result);

            assertThat(result.getResponse().getStatus()).as("key '%s'", key).isEqualTo(401);
            assertThat(refused.get("code")).isEqualTo(none.get("code"));
            assertThat(refused.get("message")).isEqualTo(none.get("message"));
        }
    }

    @Test
    @DisplayName("a valid user JWT opens nothing on /internal: it is a different scheme")
    void userTokenIsRefused() throws Exception {
        String admin = AuthenticatedRequests.validToken(UUID.randomUUID(), "admin@callverse.test", UserRole.ADMIN);

        ErrorEnvelope.assertConforms(
                body(mockMvc.perform(get(PROBE).with(bearer(admin))).andReturn()), 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("the key is read from the header only, never from the query string")
    void keyInQueryStringIsIgnored() throws Exception {
        MvcResult result =
                mockMvc.perform(get(PROBE).param("X-Internal-Key", TEST_INTERNAL_SERVICE_KEY)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("a browser preflight to /internal gets no CORS allowance, even from the allowed frontend origin")
    void browserPreflightIsNotAllowed() throws Exception {
        // Deliberately the simplest preflight: an allowed origin and an allowed method, asking for
        // no custom header. The user chains' CORS policy would admit it; this chain must not.
        RequestBuilder plain =
                options(PROBE)
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        RequestBuilder withKeyHeader =
                options(PROBE)
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-internal-key");

        for (RequestBuilder preflight : new RequestBuilder[] {plain, withKeyHeader}) {
            MvcResult result = mockMvc.perform(preflight).andReturn();
            assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        }
    }

    JsonNode body(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertThat(content)
                .as("status %d with an empty or non-JSON body", result.getResponse().getStatus())
                .startsWith("{");
        return objectMapper.readTree(content);
    }
}
