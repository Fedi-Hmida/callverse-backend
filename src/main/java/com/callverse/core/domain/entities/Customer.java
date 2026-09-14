package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.ChurnRisk;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A telecom subscriber. Maps {@code customer}. Aggregate root; {@link Contract} is reached through
 * it.
 *
 * <p>{@code user} is nullable and {@code simulated} exists because <strong>simulated customers have
 * no account</strong>. That pair of fields is what lets live mode and simulation mode share one
 * database: a KPI query filters on {@code simulated} rather than running against a separate schema,
 * and business statistics stay uncontaminated by the tens of thousands of synthetic customers an
 * experiment generates.
 */
@Entity
@Table(name = "customer")
@Getter
@Setter
@NoArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    /** Null for a simulated customer, who has no way to log in. LAZY: rarely needed. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private AppUser user;

    /** Business-facing reference, unique across live and simulated customers alike. */
    @Column(name = "external_ref", nullable = false, unique = true, length = 40)
    private String externalRef;

    @Column(name = "first_name", nullable = false, length = 80)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 80)
    private String lastName;

    @Column(name = "phone", length = 30)
    private String phone;

    /** Geographic zone, joined against {@code network_incident.zone} during technical triage. */
    @Column(name = "zone", nullable = false, length = 40)
    private String zone;

    @Column(name = "tenure_months", nullable = false)
    private int tenureMonths = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "churn_risk", nullable = false, length = 10)
    private ChurnRisk churnRisk = ChurnRisk.LOW;

    /** The pivot that keeps experiment traffic out of business statistics. */
    @Column(name = "is_simulated", nullable = false)
    private boolean simulated = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * The one collection in this block, and it earns its place: {@code Contract} has no repository
     * of its own because it lives inside this aggregate, so "show me this customer's contracts" —
     * the first thing an advisor desktop renders — has to be reachable from here.
     *
     * <p>{@link Invoice} deliberately gets no such collection: it has its own repository, because
     * the billing lifecycle runs without any customer context.
     */
    @OneToMany(mappedBy = "customer", fetch = FetchType.LAZY)
    private List<Contract> contracts = new ArrayList<>();
}
