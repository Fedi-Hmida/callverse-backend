package com.callverse.core.domain.enums;

/**
 * Codes of the reference skills seeded into the {@code skill} table.
 *
 * <p><strong>Unlike every other enum here, this one is not mapped to a column.</strong> The database
 * models skills as a table with a UUID primary key rather than as a {@code CHECK} constraint,
 * because a skill carries a label and will eventually carry routing metadata, and because an
 * operator must be able to add one without a migration.
 *
 * <p>It exists for two narrow purposes: naming the rows created by {@code V2__seed_reference.sql},
 * and giving application code a type-safe way to look a skill up by code rather than passing a bare
 * string. If a skill is ever added at runtime that has no constant here, that is expected and not
 * an error — which is exactly why this must never become a persisted column type.
 */
public enum SkillCode {

    /** Network, device and connectivity problems. */
    TECHNICAL,

    /** Invoices, payments, disputes. */
    BILLING,

    /** Offers, upgrades and retention. */
    COMMERCIAL
}
