package com.callverse.core.domain.enums;

/**
 * Lifecycle state of a simulation run. FAILED runs are kept rather than deleted, because a
 * strategy that crashes under saturation is itself a result.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum RunStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}
