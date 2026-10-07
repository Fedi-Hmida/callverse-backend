package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.ChurnRisk;
import com.callverse.core.domain.enums.CustomerSegment;
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
 * A bank customer. Maps {@code customer}. Aggregate root; {@link Account} is reached through it.
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

    /** Home region, matched against {@code service_incident.region} when triaging an outage. */
    @Column(name = "region", nullable = false, length = 40)
    private String region;

    /** The "client value" factor of the queue priority score. */
    @Enumerated(EnumType.STRING)
    @Column(name = "segment", nullable = false, length = 20)
    private CustomerSegment segment = CustomerSegment.MASS;

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
     * The one collection in this block, and it earns its place: "show me this customer's accounts"
     * is the first thing an advisor desktop renders, so it has to be reachable from here.
     *
     * <p>{@link BankTransaction} deliberately gets no such collection: it has its own repository,
     * because statements and disputes are read by account, and a customer's full history is far
     * too large to hang off the aggregate.
     */
    @OneToMany(mappedBy = "customer", fetch = FetchType.LAZY)
    private List<Account> accounts = new ArrayList<>();
}
