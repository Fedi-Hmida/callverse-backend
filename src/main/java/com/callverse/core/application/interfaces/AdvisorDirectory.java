package com.callverse.core.application.interfaces;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The advisor behind a login: what an ADVISOR caller may take and listen to.
 *
 * <p>{@code advisor.user_id} is nullable and not unique in the schema. A login linked to no
 * advisor, or to more than one, has no unambiguous advisor profile, and this returns empty rather
 * than guessing which one the caller is.
 */
public interface AdvisorDirectory {

    Optional<AdvisorProfile> findByUserId(UUID userId);

    /**
     * @param skills the skill codes this advisor holds, at any level
     */
    record AdvisorProfile(UUID id, UUID userId, String displayName, int maxConcurrent, Set<String> skills) {

        public AdvisorProfile {
            skills = Set.copyOf(skills);
        }

        public boolean holds(String skillCode) {
            return skills.contains(skillCode);
        }
    }
}
