package com.callverse.core.domain.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The service-level target for a skill. Maps {@code sla_policy}.
 *
 * <p>Expresses the classic contact-centre target: answer {@code targetRatio} of conversations within
 * {@code targetSeconds}. The seed sets 80% within 60 seconds for every skill.
 *
 * <p>This is the definition the {@code sla_ratio} KPI is measured against, so it is also the
 * yardstick a reinforcement-learning policy is ultimately judged by. Keeping it as a row rather than
 * a constant means an experiment can vary the target and observe how the policy responds.
 *
 * <p>{@code skill} is nullable: a policy with no skill is the global default.
 */
@Entity
@Table(name = "sla_policy")
@Getter
@Setter
@NoArgsConstructor
public class SlaPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    /** Null means this is the fallback policy applying to any skill without its own. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id")
    private Skill skill;

    @Column(name = "target_seconds", nullable = false)
    private int targetSeconds;

    /** NUMERIC(4,3), e.g. 0.800 for 80%. */
    @Column(name = "target_ratio", nullable = false, precision = 4, scale = 3)
    private BigDecimal targetRatio;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
