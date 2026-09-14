package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.AgentType;
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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * What an agent observed, what it did, and why. Maps {@code agent_decision}.
 *
 * <p><strong>The explainability table.</strong> A reinforcement-learning policy that improves the
 * numbers but cannot say why it acted is not defensible to a jury, to a supervisor, or to a customer
 * whose conversation it de-prioritised. Storing {@code observation} and {@code action} as the
 * literal state and action the policy saw and chose means any decision can be replayed and
 * questioned after the fact, rather than being trusted because the aggregate metrics improved.
 *
 * <p>Both are JSONB and both are {@code NOT NULL}: the observation space and action space will
 * change as the policy evolves, and a schema that had to migrate on every change would quietly
 * encourage logging less.
 *
 * <p>{@code approvedBy} is the human-in-the-loop record. A non-null value means a supervisor
 * endorsed this decision rather than the agent acting alone, which is what allows a graduated
 * rollout: the policy proposes, a human confirms, and the confirmation rate becomes evidence of
 * whether it can be trusted unsupervised.
 *
 * <p>{@code runId} may be null: agents also act in live mode, where there is no run.
 */
@Entity
@Table(name = "agent_decision")
@Getter
@Setter
@NoArgsConstructor
public class AgentDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Long id;

    /** Null in live mode. Unlike Conversation.runId this one IS a foreign key, per the schema. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id")
    private SimulationRun run;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent_type", nullable = false, length = 30)
    private AgentType agentType;

    /** Seconds since run start; null in live mode. */
    @Column(name = "sim_time")
    private Integer simTime;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "observation", nullable = false)
    private JsonNode observation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "action", nullable = false)
    private JsonNode action;

    /** Natural-language rationale, for a human reading the trace rather than for the policy. */
    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    /** Non-null when a supervisor endorsed the decision. See the class javadoc. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private AppUser approvedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
