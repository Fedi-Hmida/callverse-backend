package com.callverse.infrastructure.persistence.repositories;

import com.callverse.core.domain.entities.Scenario;
import com.callverse.core.domain.enums.LoadProfile;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Experiment reference data. The controlled variable: the same scenario across strategies is
 * what makes their results comparable.
 */
@Repository
public interface ScenarioRepository extends JpaRepository<Scenario, UUID> {

    List<Scenario> findByLoadProfile(LoadProfile loadProfile);
}
