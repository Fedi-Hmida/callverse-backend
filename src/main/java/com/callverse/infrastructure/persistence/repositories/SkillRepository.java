package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.Skill;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Reference data. The routing engine resolves a skill by code, so that rules can be written
 * against a stable string rather than a generated UUID.
 */
@Repository
public interface SkillRepository extends JpaRepository<Skill, UUID> {

    Optional<Skill> findByCode(String code);
}
