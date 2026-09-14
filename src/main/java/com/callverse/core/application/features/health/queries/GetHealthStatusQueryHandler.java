package com.callverse.core.application.features.health.queries;

import com.callverse.core.application.interfaces.PlatformMetadataProvider;
import com.callverse.core.domain.enums.ServiceStatus;
import com.callverse.core.domain.services.HeartbeatEvaluator;
import java.time.Clock;
import java.util.Objects;

/**
 * Answers {@link GetHealthStatusQuery}.
 *
 * <p><strong>This class carries no Spring annotations, and that is the point.</strong> It is plain
 * Java with constructor injection: no {@code @Service}, no {@code @Component}, no
 * {@code @Autowired}. {@code infrastructure.config.HealthFeatureConfiguration} is what turns it
 * into a bean.
 *
 * <p>Two consequences worth understanding before copying this shape into a real feature. First, it
 * can be unit-tested by calling {@code new} and passing a fake provider and a fixed clock, with no
 * application context and no mocking framework. Second, the dependency rule stays enforceable:
 * because nothing under {@code core} imports Spring, ArchUnit can assert the absence of those
 * imports as an absolute, and an absolute rule is one nobody has to interpret during review.
 *
 * <p>The clock is injected rather than read from {@link java.time.Instant#now()} for the same
 * reason: a handler that reaches for the current time is a handler whose output cannot be asserted.
 */
public class GetHealthStatusQueryHandler {

    private final PlatformMetadataProvider metadata;
    private final Clock clock;

    public GetHealthStatusQueryHandler(PlatformMetadataProvider metadata, Clock clock) {
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public HealthStatusResult handle(GetHealthStatusQuery query) {
        Objects.requireNonNull(query, "query must not be null");

        ServiceStatus status = HeartbeatEvaluator.evaluate(metadata.aiServiceConfigured());

        return new HealthStatusResult(
                metadata.serviceName(),
                metadata.version(),
                status,
                clock.instant(),
                metadata.activeProfile());
    }
}
