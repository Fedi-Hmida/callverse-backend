package com.callverse.infrastructure.persistence;

import com.callverse.core.application.interfaces.AdvisorDirectory;
import com.callverse.core.domain.entities.Advisor;
import com.callverse.infrastructure.persistence.repositories.AdvisorRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Backs {@link AdvisorDirectory} with Spring Data. */
@Component
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
class AdvisorDirectoryAdapter implements AdvisorDirectory {

    private final AdvisorRepository advisors;

    @Override
    public Optional<AdvisorProfile> findByUserId(UUID userId) {
        List<Advisor> linked = advisors.findRealByUserId(userId);
        if (linked.size() > 1) {
            // advisor.user_id is not unique in the schema; refusing beats acting as the wrong advisor.
            log.warn("Login {} is linked to {} advisors; no advisor profile is resolved", userId, linked.size());
            return Optional.empty();
        }
        return linked.stream().findFirst().map(a -> new AdvisorProfile(
                a.getId(),
                userId,
                a.getDisplayName(),
                a.getMaxConcurrent(),
                a.getSkills().stream().map(s -> s.getSkill().getCode()).collect(Collectors.toSet())));
    }
}
