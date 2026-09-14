package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.AdvisorStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Someone who handles conversations, human or simulated. Maps {@code advisor}. Aggregate root.
 *
 * <p>{@code creditLimit} is the ceiling this advisor may grant without supervisor approval.
 * <strong>The backend enforces it, never the agent.</strong> When the Customer Advisor agent calls
 * {@code apply_credit} through the {@code /internal} API, this column is what the decision is
 * checked against — an agent that believes it may grant more is simply refused. Putting the ceiling
 * in the database rather than in the agent's prompt is what makes that guarantee auditable.
 *
 * <p>{@code simulated} mirrors {@code Customer.simulated}: an experiment populates a synthetic
 * workforce, and business reporting filters it out.
 */
@Entity
@Table(name = "advisor")
@Getter
@Setter
@NoArgsConstructor
public class Advisor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    /** Null for a simulated advisor, who has no account to log in with. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AdvisorStatus status = AdvisorStatus.OFFLINE;

    /** How many conversations may be held at once. The router respects it; 1 means strictly serial. */
    @Column(name = "max_concurrent", nullable = false)
    private int maxConcurrent = 1;

    @Column(name = "credit_limit", nullable = false, precision = 8, scale = 2)
    private BigDecimal creditLimit = new BigDecimal("15.00");

    @Column(name = "is_simulated", nullable = false)
    private boolean simulated = false;

    /**
     * Bidirectional, and it earns it under Ground Rule 8. The named query is the routing engine's
     * core lookup — "available advisors holding skill S at level at least N" — and
     * {@code AdvisorSkill} has no repository of its own because it lives inside this aggregate.
     */
    @OneToMany(mappedBy = "advisor", fetch = FetchType.LAZY)
    private List<AdvisorSkill> skills = new ArrayList<>();
}
