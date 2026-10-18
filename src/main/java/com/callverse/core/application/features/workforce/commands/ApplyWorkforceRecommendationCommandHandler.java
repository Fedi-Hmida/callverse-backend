package com.callverse.core.application.features.workforce.commands;

import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.interfaces.AccessRevocation;
import com.callverse.core.application.interfaces.WorkforceAdministration;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record ApplyWorkforceRecommendationCommandHandler(WorkforceAdministration workforce, AccessRevocation revocation) {
    public WorkforceAdministration.Application handle(UUID actorId,long decisionId,List<UUID> advisorIds,int level) {
        if (advisorIds==null || advisorIds.isEmpty() || advisorIds.size()>50 || advisorIds.stream().anyMatch(id -> id==null)
                || new HashSet<>(advisorIds).size()!=advisorIds.size() || level<1 || level>3) {
            throw new InvalidRequestException("Choose distinct advisors and an explicit target skill level 1 to 3.");
        }
        var applied=workforce.apply(actorId,decisionId,List.copyOf(advisorIds),level);
        if (!applied.alreadyApplied()) applied.userIds().forEach(revocation::revoke);
        return applied;
    }
}
