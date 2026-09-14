package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.Intent;
import com.fasterxml.jackson.databind.JsonNode;
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
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A configurable rule mapping an intent onto a skill. Maps {@code routing_rule}.
 *
 * <p>Rules are data, not code, so a supervisor can change routing without a deployment. Lower
 * {@code priority} wins; the default of 100 leaves room to insert rules on either side of an
 * existing one without renumbering.
 *
 * <p>{@code conditions} is JSONB because the predicate shape is open-ended — a rule may key on a
 * customer's churn risk, an active incident in their zone, or a time window — and columns cannot be
 * added for each. It is never joined on, only evaluated after loading, which is the case JSONB fits.
 */
@Entity
@Table(name = "routing_rule")
@Getter
@Setter
@NoArgsConstructor
public class RoutingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    /** Null matches any intent. */
    @Enumerated(EnumType.STRING)
    @Column(name = "intent", length = 20)
    private Intent intent;

    /** The skill a matching conversation is routed to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id")
    private Skill skill;

    /** Lower wins. */
    @Column(name = "priority", nullable = false)
    private int priority = 100;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conditions")
    private JsonNode conditions;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
