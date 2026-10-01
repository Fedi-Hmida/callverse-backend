package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.CardBlockReason;
import com.callverse.core.domain.enums.CardNetwork;
import com.callverse.core.domain.enums.CardStatus;
import com.callverse.core.domain.enums.CardType;
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
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A payment card on an account. Maps {@code card}.
 *
 * <p><strong>PCI-DSS by construction.</strong> There is no field for the full card number, the CVV
 * or the PIN, and there must never be one: {@code panLast4} is all a customer or an advisor needs to
 * say which card they mean, and the database refuses anything but four digits.
 *
 * <p>A blocked card always records when and why ({@code chk_card_blocked}): a lost, stolen or
 * suspected-fraud call is precisely about that moment.
 */
@Entity
@Table(name = "card")
@Getter
@Setter
@NoArgsConstructor
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    /** The last four digits of the card number, and nothing more. */
    @Column(name = "pan_last4", nullable = false, length = 4)
    private String panLast4;

    @Enumerated(EnumType.STRING)
    @Column(name = "network", nullable = false, length = 20)
    private CardNetwork network;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private CardType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CardStatus status;

    @Column(name = "expires_on", nullable = false)
    private LocalDate expiresOn;

    @Column(name = "daily_limit", nullable = false, precision = 10, scale = 2)
    private BigDecimal dailyLimit;

    /** Set together with {@code blockReason} when the card is blocked. */
    @Column(name = "blocked_at")
    private Instant blockedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "block_reason", length = 30)
    private CardBlockReason blockReason;
}
