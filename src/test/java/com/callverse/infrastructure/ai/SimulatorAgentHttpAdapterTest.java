package com.callverse.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.callverse.core.application.interfaces.AdvisorAgent.HistoryMessage;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorRequest;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorState;
import com.callverse.core.domain.enums.CustomerSegment;
import com.callverse.core.domain.enums.MessageSender;
import com.callverse.core.domain.enums.SimulatorProfile;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SimulatorAgentHttpAdapterTest {

    private static final String BASE_URL = "http://simulator.test";
    private static final String API_KEY = "fake-simulator-key-for-tests";
    private final RestClient.Builder builder = RestClient.builder();
    private MockRestServiceServer server;
    private SimulatorAgentHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new SimulatorAgentHttpAdapter(builder.baseUrl(BASE_URL).build(), API_KEY);
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void sendsTheSimulationStateAndMapsAnInProgressResponse() {
        server.expect(requestTo(BASE_URL + "/ai/simulator/next-message"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-AI-Key", API_KEY))
                .andExpect(jsonPath("$.conversation_id").isNotEmpty())
                .andExpect(jsonPath("$.history[0].role").value("advisor"))
                .andExpect(jsonPath("$.history[0].ts").isNotEmpty())
                .andExpect(jsonPath("$.last_advisor_reply").value("Je vérifie."))
                .andExpect(jsonPath("$.state.profile").value("impatient"))
                .andRespond(withSuccess("""
                        {
                          "content": "Je n'ai toujours pas de réponse.",
                          "status": "en_cours",
                          "state": {
                            "profile": "impatient",
                            "patience": 35,
                            "satisfaction": 42,
                            "objective": "Comprendre un débit",
                            "objective_met": false
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        var response = adapter.nextMessage(request());

        assertThat(response.content()).isEqualTo("Je n'ai toujours pas de réponse.");
        assertThat(response.outcome().name()).isEqualTo("IN_PROGRESS");
        assertThat(response.state().profile()).isEqualTo(SimulatorProfile.IMPATIENT);
        assertThat(response.state().patience()).isEqualTo(35);
    }

    @Test
    void rejectsUnknownSimulatorStatuses() {
        server.expect(requestTo(BASE_URL + "/ai/simulator/next-message"))
                .andRespond(withSuccess("""
                        {
                          "content": null,
                          "status": "unknown",
                          "state": {
                            "profile": "calme",
                            "patience": 50,
                            "satisfaction": 50,
                            "objective": "Comprendre un débit",
                            "objective_met": false
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.nextMessage(request())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mapsRemoteErrorsToASafeAdapterFailure() {
        server.expect(requestTo(BASE_URL + "/ai/simulator/next-message")).andRespond(withServerError());

        assertThatThrownBy(() -> adapter.nextMessage(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Client Simulator is unavailable.");
    }

    private static SimulatorRequest request() {
        return new SimulatorRequest(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                List.of(new HistoryMessage(
                        MessageSender.ADVISOR, "Bonjour.", Instant.parse("2026-10-17T08:00:00Z"))),
                "Je vérifie.",
                0,
                new SimulatorState(SimulatorProfile.IMPATIENT, 55, 40, "Comprendre un débit", false));
    }
}
