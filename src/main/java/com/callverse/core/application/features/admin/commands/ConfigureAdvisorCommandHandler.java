package com.callverse.core.application.features.admin.commands;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.interfaces.AccessRevocation;
import com.callverse.core.application.interfaces.AdvisorAdministration;
import java.util.UUID;

/** Configures only the explicitly requested skills, presence and capacity, then refreshes live access. */
public record ConfigureAdvisorCommandHandler(AdvisorAdministration advisors, AccessRevocation revocation) {
    public AdvisorAdministration.Profile handle(UUID actorId, UUID userId, AdvisorAdministration.Configuration configuration) {
        if (configuration == null || configuration.status() == null || configuration.maxConcurrent() < 1
                || configuration.maxConcurrent() > 10 || configuration.skills() == null
                || configuration.skills().size() > 50
                || configuration.skills().entrySet().stream().anyMatch(skill -> skill.getKey() == null
                        || !skill.getKey().matches("[A-Z][A-Z0-9_]{0,29}")
                        || skill.getValue() == null || skill.getValue() < 1 || skill.getValue() > 3)) {
            throw new InvalidRequestException("Specify status, capacity 1 to 10, and skill levels 1 to 3 using skill codes.");
        }
        var profile = advisors.configure(actorId, userId, configuration);
        revocation.revoke(userId);
        return profile;
    }
}
