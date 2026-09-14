package com.callverse.core.domain.entities;

import com.callverse.core.domain.enums.PlanCategory;
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
 * A commercial offer a contract can point at. Maps {@code plan}. Reference data.
 *
 * <p>{@code dataGb} and {@code speedMbps} are both nullable because they are category-dependent: a
 * MOBILE plan has data and no line speed, a FIBER plan the reverse, and a BUNDLE may carry both.
 * Modelling that with one nullable column per attribute is deliberate — a subtype table per category
 * would triple the join count for an entity that is read constantly and written almost never.
 */
@Entity
@Table(name = "plan")
@Getter
@Setter
@NoArgsConstructor
public class Plan {

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
    private PlanCategory category;

    /** BigDecimal, never double: this is money and it is compared and summed. */
    @Column(name = "monthly_price", nullable = false, precision = 8, scale = 2)
    private BigDecimal monthlyPrice;

    @Column(name = "data_gb")
    private Integer dataGb;

    @Column(name = "speed_mbps")
    private Integer speedMbps;

    /** Withdrawn plans stay in the table: existing contracts still reference them. */
    @Column(name = "active", nullable = false)
    private boolean active = true;
}
