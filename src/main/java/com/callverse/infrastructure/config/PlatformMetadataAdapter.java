package com.callverse.infrastructure.config;

import com.callverse.core.application.interfaces.PlatformMetadataProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Spring-backed implementation of the {@link PlatformMetadataProvider} port.
 *
 * <p>This is the outward half of the ports-and-adapters pair. Everything framework-specific about
 * obtaining these values lives here: property injection, the {@code Environment}, the fact that the
 * version arrives via Maven resource filtering. None of it is visible to the handler that consumes
 * the port.
 */
@Component
class PlatformMetadataAdapter implements PlatformMetadataProvider {

    private final String serviceName;
    private final String version;
    private final String aiServiceBaseUrl;
    private final Environment environment;

    PlatformMetadataAdapter(
            @Value("${spring.application.name}") String serviceName,
            @Value("${callverse.version}") String version,
            @Value("${ai.service.base-url:}") String aiServiceBaseUrl,
            Environment environment) {
        this.serviceName = serviceName;
        this.version = version;
        this.aiServiceBaseUrl = aiServiceBaseUrl;
        this.environment = environment;
    }

    @Override
    public String serviceName() {
        return serviceName;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public String activeProfile() {
        String[] active = environment.getActiveProfiles();
        // An empty array means no profile was selected and Spring is using "default".
        return active.length == 0 ? "default" : String.join(",", active);
    }

    @Override
    public boolean aiServiceConfigured() {
        // Configured, not reachable. Proving reachability means an outbound HTTP call, and a
        // health endpoint that makes a network call to a third party is a health endpoint that
        // reports someone else's outage as its own. A real probe belongs in an actuator
        // HealthIndicator with its own timeout and caching, not here.
        return StringUtils.hasText(aiServiceBaseUrl);
    }
}
