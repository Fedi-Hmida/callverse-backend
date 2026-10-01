package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.AccountStatus;
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
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An account a customer holds on a banking product. Maps {@code account}.
 *
 * <p>Inside the {@link Customer} aggregate: it has no repository of its own and is reached through
 * {@code Customer.getAccounts()}, which is the first thing an advisor desktop renders.
 *
 * <p><strong>The IBAN never leaves the backend whole</strong> on a customer read; the host layer
 * masks it. {@code openedAt} and {@code closedAt} are {@link LocalDate}: an account opens on a
 * calendar day, and storing it as an instant would invite a timezone to move it. The database
 * enforces {@code chk_account_dates}.
 */
@Entity
@Table(name = "account")
@Getter
@Setter
@NoArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    /** LAZY: JPA's @ManyToOne default is EAGER, which would load the customer on every account. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private BankingProduct product;

    @Column(name = "iban", nullable = false, unique = true, length = 34)
    private String iban;

    /** ISO 4217 code; the database checks its shape. */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    /** Negative on an overdrawn current account and on a loan's outstanding capital. */
    @Column(name = "balance", nullable = false, precision = 14, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "overdraft_limit", nullable = false, precision = 10, scale = 2)
    private BigDecimal overdraftLimit = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "opened_at", nullable = false)
    private LocalDate openedAt;

    /** Null while the account is open. */
    @Column(name = "closed_at")
    private LocalDate closedAt;
}
