package com.callverse.internal;

import static com.callverse.auth.AuthenticatedRequests.serviceKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.callverse.core.domain.entities.ServiceIncident;
import com.callverse.core.domain.enums.BankingService;
import com.callverse.persistence.AbstractPersistenceTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
@ActiveProfiles("prod")
class InternalServiceIncidentsEndpointTest extends AbstractPersistenceTest {

    private static final String PATH = "/internal/service-incidents";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager em;

    @Test
    @Transactional
    void serviceKeyCanFilterLiveIncidentsByBankingService() throws Exception {
        persist(BankingService.CARD_PAYMENTS, "card payment outage");
        persist(BankingService.ONLINE_BANKING, "online banking outage");
        em.flush();
        em.clear();

        MvcResult result = mockMvc.perform(
                        get(PATH).param("service", "CARD_PAYMENTS").with(serviceKey()))
                .andReturn();
        String raw = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus()).as("body was: %s", raw).isEqualTo(200);
        JsonNode incidents = objectMapper.readTree(raw).get("incidents");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).get("service").asText()).isEqualTo("CARD_PAYMENTS");
        assertThat(incidents.get(0).get("description").asText()).isEqualTo("card payment outage");
    }

    private void persist(BankingService service, String description) {
        ServiceIncident incident = new ServiceIncident();
        incident.setService(service);
        incident.setSeverity((short) 2);
        incident.setDescription(description);
        incident.setStartedAt(Instant.now().minusSeconds(600));
        em.persist(incident);
    }
}
