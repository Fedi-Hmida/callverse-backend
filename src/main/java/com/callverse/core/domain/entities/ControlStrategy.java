package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.StrategyKind;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A way of deciding how work is routed and staffed. Maps {@code control_strategy}.
 *
 * <p>The experimental independent variable. {@code STATIC_FIFO} is the BASELINE, and its existence
 * as a first-class row is what makes the project's central claim testable: "the reinforcement
 * learning policy improves service level" means nothing until there is a measured baseline in the
 * same database, run against the same scenarios with the same seeds.
 *
 * <p>{@code params} is JSONB because each kind is configured differently — a threshold heuristic has
 * numeric cutoffs, an RL policy has a checkpoint reference and hyperparameters — and a column per
 * strategy family would need a migration for every new strategy.
 */
@Entity
@Table(name = "control_strategy")
@Getter
@Setter
@NoArgsConstructor
public class ControlStrategy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    /** STATIC_FIFO, THRESHOLD, RL_PPO_V1. Stable: results are reported against it. */
    @Column(name = "code", nullable = false, unique = true, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private StrategyKind kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params")
    private JsonNode params;
}
