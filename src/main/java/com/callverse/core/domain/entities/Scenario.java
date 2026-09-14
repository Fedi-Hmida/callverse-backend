package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.LoadProfile;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The conditions an experiment is run under. Maps {@code scenario}.
 *
 * <p>The experimental controlled variable: the same scenario run against different strategies is
 * what makes their results comparable. {@code SATURATED} is the interesting case — under light load
 * every strategy looks identical, and only when arrivals exceed capacity does routing policy change
 * the outcome.
 *
 * <p>Four JSONB columns, all describing distributions rather than entities:
 * {@code skillDistribution} (what proportion of arrivals need which skill),
 * {@code customerProfileMix} (churn risk and tenure mix of the synthetic population), and
 * {@code injectedEvents} (outages and surges fired at set times). They are configuration read whole
 * by the simulation runner, never queried into, which is exactly what JSONB is for.
 */
@Entity
@Table(name = "scenario")
@Getter
@Setter
@NoArgsConstructor
public class Scenario {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "load_profile", nullable = false, length = 20)
    private LoadProfile loadProfile;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    /** Size of the synthetic workforce this scenario staffs. */
    @Column(name = "advisor_count", nullable = false)
    private int advisorCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_distribution", nullable = false)
    private JsonNode skillDistribution;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "customer_profile_mix", nullable = false)
    private JsonNode customerProfileMix;

    /** Optional: outages, traffic spikes and other shocks fired at set simulation times. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "injected_events")
    private JsonNode injectedEvents;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
