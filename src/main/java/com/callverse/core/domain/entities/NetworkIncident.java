package com.callverse.core.domain.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A network outage affecting a geographic zone. Maps {@code network_incident}.
 *
 * <p>Joined against {@link Customer#getZone()} during technical triage: an advisor, or the Customer
 * Advisor agent, needs to know that this customer's problem is a known outage rather than a fault on
 * their line. {@code idx_incident_zone_active} is partial on {@code resolved_at IS NULL}, because
 * only unresolved incidents are ever looked up this way and resolved ones accumulate forever.
 *
 * <p>{@code runId} is a plain UUID with no foreign key, exactly as on {@link Conversation}: an
 * experiment can inject synthetic incidents as a scenario event, and the same table then carries
 * both real and simulated outages without the business universe gaining a dependency on the
 * experiment universe.
 */
@Entity
@Table(name = "network_incident")
@Getter
@Setter
@NoArgsConstructor
public class NetworkIncident {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Column(name = "zone", nullable = false, length = 40)
    private String zone;

    /** Free text in the schema; no CHECK constraint and no enum. Left as specified. */
    @Column(name = "type", nullable = false, length = 30)
    private String type;

    @Column(name = "severity", nullable = false)
    private short severity;

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
