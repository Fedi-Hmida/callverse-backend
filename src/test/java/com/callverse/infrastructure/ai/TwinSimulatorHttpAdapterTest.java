package com.callverse.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.callverse.core.application.interfaces.TwinSimulator.TwinKpis;
import com.callverse.core.application.interfaces.TwinSimulator.TwinRunSpec;
import com.callverse.core.domain.enums.LoadProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TwinSimulatorHttpAdapterTest {

    private static final String BASE_URL = "http://twin.test";
    private static final String API_KEY = "fake-ai-key-for-tests-only";
    private static final TwinRunSpec SPEC = new TwinRunSpec("PPO", 7, LoadProfile.SATURATED, 480, 3);

    private final RestClient.Builder builder = RestClient.builder();
    private MockRestServiceServer server;
    private TwinSimulatorHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new TwinSimulatorHttpAdapter(builder.baseUrl(BASE_URL).build(), API_KEY);
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void sendsTheScenarioAndMapsTheKpis() {
        server.expect(requestTo(BASE_URL + "/ai/workforce/twin-run"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-AI-Key", API_KEY))
                .andExpect(jsonPath("$.strategy").value("PPO"))
                .andExpect(jsonPath("$.seed").value(7))
                .andExpect(jsonPath("$.load_profile").value("SATURATED"))
                .andExpect(jsonPath("$.duration_minutes").value(480))
                .andExpect(jsonPath("$.advisors_per_skill").value(3))
                .andRespond(withSuccess("""
                        {"strategy":"PPO","seed":7,"load_profile":"SATURATED","arrived":431,"served":402,
                         "sla_rate":0.91,"sla_all_arrivals":0.85,"abandon_rate":0.067,"avg_wait_seconds":41.2,
                         "p95_wait_seconds":158.8,"pending":0,"reassignments":12}
                        """, MediaType.APPLICATION_JSON));

        assertThat(adapter.run(SPEC)).isEqualTo(new TwinKpis(431, 402, 0.91, 0.85, 0.067, 41.2, 158.8, 12));
    }

    @Test
    void aServerErrorOrAnIncompleteAnswerIsAFailure() {
        server.expect(requestTo(BASE_URL + "/ai/workforce/twin-run")).andRespond(withServerError());
        assertThatThrownBy(() -> adapter.run(SPEC)).isInstanceOf(IllegalStateException.class);

        server.reset();
        server.expect(requestTo(BASE_URL + "/ai/workforce/twin-run"))
                .andRespond(withSuccess("{\"strategy\":\"PPO\",\"arrived\":431}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.run(SPEC)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ratesOutsideZeroToOneAreRejected() {
        server.expect(requestTo(BASE_URL + "/ai/workforce/twin-run"))
                .andRespond(withSuccess("""
                        {"strategy":"PPO","seed":7,"load_profile":"SATURATED","arrived":10,"served":9,
                         "sla_rate":1.4,"sla_all_arrivals":0.85,"abandon_rate":0.1,"avg_wait_seconds":4.0,
                         "p95_wait_seconds":8.0,"pending":0,"reassignments":0}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.run(SPEC)).isInstanceOf(IllegalStateException.class);
    }
}
