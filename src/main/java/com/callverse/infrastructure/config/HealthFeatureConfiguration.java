package com.callverse.infrastructure.config;

import com.callverse.core.application.features.health.queries.GetHealthStatusQueryHandler;
import com.callverse.core.application.interfaces.PlatformMetadataProvider;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the health feature's use case into the application context.
 *
 * <p><strong>This is the wiring pattern for the whole team. Copy this file's shape for every
 * feature slice.</strong>
 *
 * <p>The rule is one sentence: <em>use cases stay framework-free, and infrastructure wires them.</em>
 * A handler under {@code core.application.features} is a plain class with a constructor. It becomes
 * a Spring bean here, in {@code infrastructure.config}, and nowhere else.
 *
 * <p>The temptation is to skip this file and put {@code @Service} on the handler instead. It is one
 * annotation and it works. It also costs three things that are hard to get back once a dozen
 * handlers have done it:
 *
 * <ul>
 *   <li>The dependency rule stops being absolute. "Core must not import Spring" is a rule ArchUnit
 *       can check and nobody has to argue about in review; "core must not import Spring except the
 *       stereotype annotations" is a rule that erodes, because the next exception always has an
 *       equally good reason.
 *   <li>Handlers become awkward to test. A plain constructor takes a fake provider and a fixed
 *       clock; an annotated one invites a test that starts a context to get one object.
 *   <li>The use cases stop being portable. Today the only delivery mechanism is Spring MVC. The
 *       simulation runner and the scheduled SLA sweeps call the same handlers without going through
 *       HTTP at all, and a handler that is only constructible by a container is a handler those
 *       callers have to work around.
 * </ul>
 *
 * <p>The cost is this file: roughly five lines per handler, in a place where a reader can see the
 * whole feature's dependency graph at once.
 */
@Configuration
public class HealthFeatureConfiguration {

    /**
     * UTC, deliberately. Every timestamp this system emits is ISO-8601 UTC, so that a KPI compared
     * across two simulation runs is not quietly measuring a daylight-saving transition.
     *
     * <p>Declared as a bean rather than called directly inside handlers so that a test can replace
     * it with {@link Clock#fixed}.
     */
    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    GetHealthStatusQueryHandler getHealthStatusQueryHandler(
            PlatformMetadataProvider metadata, Clock clock) {
        return new GetHealthStatusQueryHandler(metadata, clock);
    }
}
