package com.callverse.core.domain.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Composite key of {@link MetricSample}: {@code (run_id, sim_time, skill_id)}.
 *
 * <p>The second and last class in this package with {@code equals}/{@code hashCode}, for the same
 * reason as {@link AdvisorSkillId}: JPA requires a composite id to be equal-by-value.
 *
 * <p><strong>{@code skillId} is not nullable, despite the schema declaring the column as a nullable
 * foreign key.</strong> PostgreSQL forces primary-key columns {@code NOT NULL} regardless of how
 * they are declared, so a cross-skill aggregate sample is not representable. This was raised with
 * the schema's author and left as specified; it is recorded here because a developer reading the
 * migration would otherwise reasonably expect to be able to write one.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MetricSampleId implements Serializable {

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    /** Seconds since the run started. Sampled every 10s, not every second — see {@link MetricSample}. */
    @Column(name = "sim_time", nullable = false)
    private Integer simTime;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MetricSampleId that)) {
            return false;
        }
        return Objects.equals(runId, that.runId)
                && Objects.equals(simTime, that.simTime)
                && Objects.equals(skillId, that.skillId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runId, simTime, skillId);
    }
}
