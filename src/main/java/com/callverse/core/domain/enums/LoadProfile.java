package com.callverse.core.domain.enums;

/**
 * Traffic intensity a simulation scenario generates. SATURATED means arrivals deliberately
 * exceed capacity, which is the condition under which routing strategies actually differ.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum LoadProfile {
    LOW,
    MEDIUM,
    SATURATED
}
