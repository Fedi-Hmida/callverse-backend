package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.ContractStatus;
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
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A subscription binding a customer to a plan. Maps {@code contract}.
 *
 * <p>Inside the {@link Customer} aggregate: it has no repository of its own and is reached through
 * {@code Customer.getContracts()}.
 *
 * <p>{@code startedAt} and {@code endedAt} are {@link LocalDate}, not {@code Instant}: a contract
 * starts on a calendar day agreed with the customer, not at an instant, and storing it as one would
 * invite a timezone to change which day it appears to be.
 *
 * <p>The database additionally enforces {@code chk_contract_dates}: an end date, when present, is
 * not before the start date. That is checked in the database rather than only here because an
 * invariant enforced only in application code is an invariant the next data import violates.
 */
@Entity
@Table(name = "contract")
@Getter
@Setter
@NoArgsConstructor
public class Contract {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    /** LAZY: JPA's @ManyToOne default is EAGER, which would load the customer on every contract. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ContractStatus status;

    @Column(name = "started_at", nullable = false)
    private LocalDate startedAt;

    /** Null while the contract is running. */
    @Column(name = "ended_at")
    private LocalDate endedAt;
}
