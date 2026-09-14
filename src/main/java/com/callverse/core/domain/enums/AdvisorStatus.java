package com.callverse.core.domain.enums;

/**
 * Real-time availability of an advisor. The router may assign work only to an advisor who is
 * AVAILABLE, or to one who is BUSY but below their max_concurrent ceiling.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum AdvisorStatus {
    AVAILABLE,
    BUSY,
    BREAK,
    OFFLINE
}
