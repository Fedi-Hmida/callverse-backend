package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.BankingService;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An outage of a banking service. Maps {@code service_incident}.
 *
 * <p>An advisor, or the Customer Advisor agent, needs to know that a declined card or a failed
 * transfer is a known outage rather than a problem with this customer's account.
 * {@code region} is null for a national outage, which is why a regional question must also return
 * the incidents whose region is null. {@code idx_service_incident_active} is partial on
 * {@code resolved_at IS NULL}: only unresolved incidents are ever looked up.
 *
 * <p>{@code runId} is a plain UUID with no foreign key, exactly as on {@link Conversation}: a
 * scenario can inject a synthetic outage, and the same table then carries real and simulated ones
 * without the business universe depending on the experiment universe.
 */
@Entity
@Table(name = "service_incident")
@Getter
@Setter
@NoArgsConstructor
public class ServiceIncident {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "service", nullable = false, length = 30)
    private BankingService service;

    /** Null when the outage is national. */
    @Column(name = "region", length = 40)
    private String region;

    @Column(name = "severity", nullable = false)
    private short severity;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** The advertised restoration time, which is what a customer is actually told. */
    @Column(name = "estimated_end")
    private Instant estimatedEnd;

    /** Null while ongoing. The partial index depends on this staying null until resolution. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** Non-null when injected by a simulation scenario. No foreign key, by design. */
    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "affected_count")
    private Integer affectedCount;
}
