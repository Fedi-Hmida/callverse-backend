package com.callverse.core.domain.enums;

/**
 * Why a card was blocked. A blocked card always carries one: {@code chk_card_blocked}.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum CardBlockReason {
    LOST,
    STOLEN,
    FRAUD_SUSPECTED,
    CUSTOMER_REQUEST
}
