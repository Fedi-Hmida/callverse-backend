package com.callverse.core.application.features.admin.queries;

import com.callverse.core.application.interfaces.AdvisorAdministration;
import java.util.UUID;

public record GetAdvisorProfileQueryHandler(AdvisorAdministration advisors) {
    public AdvisorAdministration.Profile handle(UUID actorId, UUID userId) {
        return advisors.profile(actorId, userId);
    }
}
