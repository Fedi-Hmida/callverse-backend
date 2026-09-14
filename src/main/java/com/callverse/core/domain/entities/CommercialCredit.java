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
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A goodwill gesture granted to a customer. Maps {@code commercial_credit}.
 *
 * <p>This is the audit trail behind the rule the project cares most about: <strong>the backend, not
 * the agent, decides what may be granted.</strong> When the Customer Advisor agent calls
 * {@code apply_credit}, the amount is checked against {@link Advisor#getCreditLimit()}; anything
 * above it requires a supervisor, recorded in {@code approvedBy}. A row with a non-null
 * {@code approvedBy} is therefore a credit that exceeded an advisor's ceiling and was signed off,
 * and the pair of columns makes that reviewable after the fact rather than merely asserted.
 *
 * <p>{@code amount} is constrained positive in the database: a negative credit is a charge, and
 * charging a customer through the goodwill path would be a serious bug worth making unrepresentable.
 */
@Entity
@Table(name = "commercial_credit")
@Getter
@Setter
@NoArgsConstructor
public class CommercialCredit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /** The conversation it was granted during, if any. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    private Conversation conversation;

    @Column(name = "amount", nullable = false, precision = 8, scale = 2)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    /** The advisor, human or simulated, whose ceiling this was charged against. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "granted_by")
    private Advisor grantedBy;

    /** Non-null only when the amount exceeded the advisor's ceiling and a supervisor approved it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private AppUser approvedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
