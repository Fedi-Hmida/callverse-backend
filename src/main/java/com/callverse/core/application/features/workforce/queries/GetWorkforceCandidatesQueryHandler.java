package com.callverse.core.application.features.workforce.queries;

import com.callverse.core.application.interfaces.WorkforceAdministration;
import java.util.UUID;

public record GetWorkforceCandidatesQueryHandler(WorkforceAdministration workforce) {
    public WorkforceAdministration.Selection handle(UUID actorId,long decisionId) {
        return workforce.candidates(actorId,decisionId);
    }
}
