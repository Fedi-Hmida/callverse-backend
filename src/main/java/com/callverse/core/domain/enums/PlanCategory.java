package com.callverse.core.domain.enums;

/**
 * Commercial family a telecom plan belongs to. BUNDLE denotes a combined offer rather than a
 * single access technology.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum PlanCategory {
    MOBILE,
    FIBER,
    ADSL,
    BUNDLE
}
