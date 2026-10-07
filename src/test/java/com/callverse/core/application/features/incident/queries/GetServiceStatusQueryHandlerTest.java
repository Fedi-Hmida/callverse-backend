package com.callverse.core.application.features.incident.queries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.interfaces.ServiceIncidents;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How the handler shapes the question it puts to the port. */
class GetServiceStatusQueryHandlerTest {

    private static final class FakeIncidents implements ServiceIncidents {
        String askedRegion = "unset";
        UUID askedRun;

        @Override
        public List<ActiveIncident> findActive(String region, UUID runId) {
            askedRegion = region;
            askedRun = runId;
            return List.of();
        }
    }

    private final FakeIncidents incidents = new FakeIncidents();
    private final GetServiceStatusQueryHandler handler = new GetServiceStatusQueryHandler(incidents);

    @Test
    @DisplayName("no region asks about every region, in the live system")
    void noRegionMeansEveryRegion() {
        handler.handle(new GetServiceStatusQuery(null, null));
        assertThat(incidents.askedRegion).isNull();
        assertThat(incidents.askedRun).isNull();
    }

    @Test
    @DisplayName("a region is trimmed before it is asked")
    void regionIsTrimmed() {
        handler.handle(new GetServiceStatusQuery("  Marseille ", null));
        assertThat(incidents.askedRegion).isEqualTo("Marseille");
    }

    @Test
    @DisplayName("a blank region is a caller mistake, not a wildcard")
    void blankRegionIsRefused() {
        assertThatThrownBy(() -> handler.handle(new GetServiceStatusQuery("   ", null)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("a named run is passed through, so only that run's incidents come back")
    void runIsPassedThrough() {
        UUID run = UUID.randomUUID();
        handler.handle(new GetServiceStatusQuery("Lyon", run));
        assertThat(incidents.askedRun).isEqualTo(run);
    }
}
