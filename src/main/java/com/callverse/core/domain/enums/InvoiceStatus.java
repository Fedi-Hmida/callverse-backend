package com.callverse.core.domain.enums;

/**
 * Billing state of an issued invoice. DISPUTED is distinct from OVERDUE: the customer has
 * contested the amount rather than simply not paid it, and the two lead to different
 * conversations.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum InvoiceStatus {
    PENDING,
    PAID,
    OVERDUE,
    DISPUTED
}
