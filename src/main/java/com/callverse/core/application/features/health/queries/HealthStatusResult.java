package com.callverse.core.application.features.health.queries;

import com.callverse.core.domain.enums.ServiceStatus;
import java.time.Instant;

/**
 * What the health use case returns.
 *
 * <p>Note that this is not the shape the API returns. {@code host.api.dto.response} has its own
 * record, and a mapper sits between them. For a payload this small that separation looks like
 * ceremony, and for exactly one endpoint it is; it is here because it is the pattern the rest of
 * the system follows, and the walking skeleton exists to demonstrate the pattern rather than to
 * find the shortest path to a JSON body.
 *
 * <p>It lives in its feature slice, not in a shared {@code dto/result} package. Command and result
 * records belong to the use case that owns them; a package named after a technical role is the
 * thing feature slicing exists to avoid, and at thirty features a shared bucket answers "where does
 * this record go?" differently for every developer who asks.
 *
 * @param service name of this service
 * @param version deployed build version
 * @param status business-level platform state
 * @param timestamp instant the reading was taken, always UTC
 * @param profile active Spring profile
 */
public record HealthStatusResult(
        String service,
        String version,
        ServiceStatus status,
        Instant timestamp,
        String profile) {}
