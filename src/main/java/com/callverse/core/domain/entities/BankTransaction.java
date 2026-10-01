package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.TransactionStatus;
import com.callverse.core.domain.enums.TransactionType;
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
 * One movement on an account. Maps {@code bank_transaction} ({@code transaction} alone is an SQL
 * keyword).
 *
 * <p>Its own aggregate root, unlike {@link Account}: statement and dispute handling read it by
 * account without loading the customer, and {@code idx_txn_account_booked} serves "this account's
 * movements, most recent first" directly.
 *
 * <p>{@code amount} is signed. A negative value debits the account, so a statement sums without
 * consulting {@code type}, and the database refuses a zero movement.
 */
@Entity
@Table(name = "bank_transaction")
@Getter
@Setter
@NoArgsConstructor
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    /** The card used, for a card payment or an ATM withdrawal; null otherwise. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_id")
    private Card card;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private TransactionType type;

    /** Money: BigDecimal with the column's exact precision and scale, never double. Signed. */
    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    /** What the customer sees on their statement. */
    @Column(name = "label", nullable = false, length = 140)
    private String label;

    @Column(name = "counterparty", length = 140)
    private String counterparty;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "booked_at", nullable = false, updatable = false)
    private Instant bookedAt = Instant.now();
}
