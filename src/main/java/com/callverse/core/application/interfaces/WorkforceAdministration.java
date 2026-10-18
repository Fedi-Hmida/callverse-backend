package com.callverse.core.application.interfaces;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Human-selected, auditable application of an agent's staffing proposal. */
public interface WorkforceAdministration {
    Selection candidates(UUID actorId, long decisionId);
    Application apply(UUID actorId, long decisionId, List<UUID> advisorIds, int targetSkillLevel);
    record Candidate(UUID id, UUID userId, String displayName, String status, int maxConcurrent, Map<String,Integer> skills) {}
    record Selection(long decisionId, String fromPool, String toPool, int count, boolean applied,
                     List<UUID> appliedAdvisorIds, List<Candidate> candidates) {}
    record Application(long decisionId, boolean alreadyApplied, Instant appliedAt, UUID approvedBy,
                       String fromPool, String toPool, int targetSkillLevel,
                       List<UUID> advisorIds, List<UUID> userIds) {}
}
