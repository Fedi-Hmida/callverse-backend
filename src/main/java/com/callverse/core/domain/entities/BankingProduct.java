package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.ProductCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A product an account can be opened on. Maps {@code banking_product}. Reference data.
 *
 * <p>{@code interestRate} is nullable because it is category-dependent: a savings product pays one,
 * a loan charges one, a current account has none. It is an annual rate expressed as a fraction
 * ({@code 0.0350} is 3.5 %), never a percentage, so a calculation never has to guess which of the
 * two it was given.
 */
@Entity
@Table(name = "banking_product")
@Getter
@Setter
@NoArgsConstructor
public class BankingProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private ProductCategory category;

    /** BigDecimal, never double: this is money and it is compared and summed. */
    @Column(name = "monthly_fee", nullable = false, precision = 8, scale = 2)
    private BigDecimal monthlyFee = BigDecimal.ZERO;

    /** Annual rate as a fraction; null where the product carries none. */
    @Column(name = "interest_rate", precision = 6, scale = 4)
    private BigDecimal interestRate;

    /** Withdrawn products stay in the table: open accounts still reference them. */
    @Column(name = "active", nullable = false)
    private boolean active = true;
}
