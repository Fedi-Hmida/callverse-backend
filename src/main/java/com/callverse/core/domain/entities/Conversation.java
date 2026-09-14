package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.Channel;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.Intent;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One customer interaction, from arrival in the queue to resolution or abandonment. Maps
 * {@code conversation}. The core aggregate root of the whole system.
 *
 * <h2>Three decisions that are not obvious</h2>
 *
 * <p><strong>1. {@code runId} is a plain UUID, not an association and not a foreign key.</strong>
 * Null means live mode, non-null means the conversation belongs to a simulation run. This is the
 * pivot between the two universes the schema keeps apart, and the schema document declares no
 * {@code REFERENCES} on it. Mapping it as a {@code @ManyToOne SimulationRun} would let the business
 * universe traverse into the experiment universe from any conversation, and would couple insert
 * ordering between the two during the burst writes a simulation produces.
 *
 * <p><strong>2. {@code waitSeconds}, {@code handleSeconds} and {@code slaMet} are denormalized on
 * purpose.</strong> They are derivable from the timestamps, and deriving them is exactly the
 * problem: a KPI query across tens of thousands of conversations would recompute them every time.
 * They are written once, at the state transition that settles them, and read forever after. All
 * three are nullable because none is knowable until that transition happens.
 *
 * <p><strong>3. There is no messages collection.</strong> {@link Message} is the highest-volume
 * table in the schema and has its own repository. Loading a conversation in order to append one
 * message, or worse to count them, is the N+1 this omission prevents.
 *
 * <p>Legal transitions between {@code status} values live on {@link ConversationStatus}. Nothing
 * enforces them yet; the conversation feature slice will.
 */
@Entity
@Table(name = "conversation")
@Getter
@Setter
@NoArgsConstructor
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /** Null while QUEUED. Set at assignment, cleared to null if the advisor's account is deleted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "advisor_id")
    private Advisor advisor;

    /** The skill this conversation requires. Second column of {@code idx_conv_status_queue}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id")
    private Skill skill;

    /** NULL in live mode. See decision 1 above. */
    @Column(name = "run_id")
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private Channel channel = Channel.CHAT;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ConversationStatus status;

    /** Null until the Customer Advisor agent has classified the conversation. */
    @Enumerated(EnumType.STRING)
    @Column(name = "intent", length = 20)
    private Intent intent;

    /** Third column of {@code idx_conv_status_queue}, sorted DESC: higher is served first. */
    @Column(name = "priority_score", nullable = false, precision = 6, scale = 2)
    private BigDecimal priorityScore = BigDecimal.ZERO;

    @Column(name = "queued_at", nullable = false, updatable = false)
    private Instant queuedAt = Instant.now();

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    /** Denormalized at assignment. See decision 2. */
    @Column(name = "wait_seconds")
    private Integer waitSeconds;

    /** Denormalized at close. See decision 2. */
    @Column(name = "handle_seconds")
    private Integer handleSeconds;

    /** Denormalized at assignment against the skill's SLA policy. See decision 2. */
    @Column(name = "sla_met")
    private Boolean slaMet;
}
