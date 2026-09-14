package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.Advisor;
import com.callverse.core.domain.enums.AdvisorStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Advisor aggregate root. {@code advisor_skill} rows are reached through {@code Advisor.getSkills()}
 * and have no repository of their own.
 */
@Repository
public interface AdvisorRepository extends JpaRepository<Advisor, UUID> {

    /**
     * The routing engine's core lookup: who can take this conversation.
     *
     * <p>This is the query that justifies {@code Advisor} carrying a bidirectional {@code skills}
     * collection at all, under the rule that a collection must name the query needing it.
     *
     * <p>The {@code level} filter is not decoration: a level-3 advisor handling a level-1 problem is
     * a misallocation the Workforce Manager should be able to avoid, and ordering by level
     * descending lets a caller prefer the most capable while still seeing everyone eligible.
     */
    @Query("""
           select distinct a
             from Advisor a
             join a.skills s
            where s.skill.id = :skillId
              and s.level >= :minLevel
              and a.status = :status
            order by s.level desc
           """)
    List<Advisor> findEligibleForSkill(
            @Param("skillId") UUID skillId,
            @Param("minLevel") short minLevel,
            @Param("status") AdvisorStatus status);

    List<Advisor> findByStatus(AdvisorStatus status);

    /** Excludes the synthetic workforce an experiment creates, for business reporting. */
    List<Advisor> findBySimulatedFalse();

    /** The staffing count a simulation run works with. */
    long countBySimulatedTrueAndStatus(AdvisorStatus status);
}
