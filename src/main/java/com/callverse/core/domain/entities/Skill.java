package com.callverse.core.domain.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A competence a conversation can require and an advisor can hold. Maps {@code skill}.
 *
 * <p>A table rather than an enum column, deliberately: a skill carries a display label, will
 * eventually carry routing metadata, and an operator must be able to add one without a migration.
 * {@code SkillCode} exists in the enums package for type-safe lookup of the seeded codes, but is
 * never persisted — a skill created at runtime with no matching constant is expected, not an error.
 */
@Entity
@Table(name = "skill")
@Getter
@Setter
@NoArgsConstructor
public class Skill {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    /** ACCOUNTS, CARDS, CREDIT, FRAUD. Stable; routing rules are written against it. */
    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    /** Display text, French, shown to advisors and supervisors. */
    @Column(name = "label", nullable = false, length = 80)
    private String label;
}
