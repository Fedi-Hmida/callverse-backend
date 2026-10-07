package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.EscalationRaisedBy;
import com.callverse.core.domain.enums.EscalationStatus;
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
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A request for supervisor intervention on a conversation. Maps {@code escalation}.
 *
 * <p>{@code raisedBy} records which of the three sources triggered it — a human advisor asking for
 * help, an AI agent recognising it is out of depth, or an automatic rule such as an SLA breach. That
 * distinction is a result in its own right: how often the Customer Advisor agent escalates
 * unprompted, versus how often a rule has to catch it, is one of the measures of whether the agent
 * knows its own limits.
 *
 * <p>Note that {@link com.callverse.core.domain.enums.ConversationStatus#ESCALATED} cannot transition
 * to ABANDONED. Once a supervisor owns a conversation it is seen through.
 */
@Entity
@Table(name = "escalation")
@Getter
@Setter
@NoArgsConstructor
public class Escalation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "raised_by", nullable = false, length = 20)
    private EscalationRaisedBy raisedBy;

    /** The supervisor who took it. Null while PENDING. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private AppUser resolvedBy;

    /**
     * Mapped as an enum even though this column, unlike its siblings, carries no CHECK constraint in
     * the schema. Raised with the schema's author; left as specified, so the database will accept a
     * value this enum cannot represent and reading such a row would fail at the mapping layer.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EscalationStatus status = EscalationStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    /** Microseconds, what PostgreSQL stores: the first response and every later read agree. */
    private Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
