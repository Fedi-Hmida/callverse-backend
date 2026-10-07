package com.callverse.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.domain.entities.ServiceIncident;
import com.callverse.core.domain.enums.BankingService;
import com.callverse.infrastructure.persistence.repositories.ServiceIncidentRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The outage queries against real rows: a regional question must include national outages, and the
 * live system and a simulation run must never see each other's incidents.
 */
@Transactional
class ServiceIncidentQueriesTest extends AbstractPersistenceTest {

    @Autowired EntityManager em;
    @Autowired ServiceIncidentRepository repository;

    private final UUID run = UUID.randomUUID();
    private final UUID otherRun = UUID.randomUUID();
    private String region;

    @BeforeEach
    void seed() {
        region = "R-" + UUID.randomUUID().toString().substring(0, 8);
        persist("regional live", region, null, null);
        persist("national live", null, null, null);
        persist("other region live", "R-elsewhere", null, null);
        persist("resolved live", region, null, Instant.now());
        persist("regional in run", region, run, null);
        persist("national in run", null, run, null);
        persist("regional in another run", region, otherRun, null);
        em.flush();
        em.clear();
    }

    private void persist(String description, String inRegion, UUID runId, Instant resolvedAt) {
        ServiceIncident incident = new ServiceIncident();
        incident.setService(BankingService.ONLINE_BANKING);
        incident.setRegion(inRegion);
        incident.setSeverity((short) 2);
        incident.setDescription(description);
        incident.setStartedAt(Instant.now().minusSeconds(600));
        incident.setResolvedAt(resolvedAt);
        incident.setRunId(runId);
        em.persist(incident);
    }

    @Test
    @DisplayName("a live regional question returns that region's outages and the national ones only")
    void liveRegionIncludesNational() {
        assertThat(repository.findActiveLiveInRegion(region))
                .extracting(ServiceIncident::getDescription)
                .contains("regional live", "national live")
                .doesNotContain(
                        "other region live",
                        "resolved live",
                        "regional in run",
                        "national in run",
                        "regional in another run");
    }

    @Test
    @DisplayName("a run sees its own incidents, national ones included, and nothing from live or another run")
    void runIsIsolated() {
        assertThat(repository.findActiveInRunInRegion(run, region))
                .extracting(ServiceIncident::getDescription)
                .containsExactlyInAnyOrder("regional in run", "national in run");
    }
}
