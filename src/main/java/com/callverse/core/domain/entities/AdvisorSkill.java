package com.callverse.core.domain.entities;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * How well an advisor holds a skill. Maps {@code advisor_skill}.
 *
 * <p><strong>An entity, not a {@code @ManyToMany}.</strong> The association carries a payload —
 * {@code level} — and a {@code @ManyToMany} has nowhere to put it. That is the whole reason this
 * class exists, and it is also why there is no {@code @ManyToMany} anywhere in this package: the
 * moment an association needs an attribute, the mapping has to be rewritten as an entity, and doing
 * it up front costs one class and saves a migration.
 *
 * <p>{@code @MapsId} on each association ties the two foreign keys to the two halves of the
 * composite id, so the id fields and the associations stay one source of truth rather than two that
 * can disagree.
 */
@Entity
@Table(name = "advisor_skill")
@Getter
@Setter
@NoArgsConstructor
public class AdvisorSkill {

    @EmbeddedId
    private AdvisorSkillId id = new AdvisorSkillId();

    @MapsId("advisorId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "advisor_id", nullable = false)
    private Advisor advisor;

    @MapsId("skillId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "skill_id", nullable = false)
    private Skill skill;

    /**
     * 1 to 3, enforced by a CHECK constraint. Routing prefers a higher level when more than one
     * advisor is eligible, so this is a ranking input and not merely descriptive.
     */
    @Column(name = "level", nullable = false)
    private short level;
}
