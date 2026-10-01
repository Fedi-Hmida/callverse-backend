package com.callverse.core.domain.enums;

/**
 * A customer-facing banking service that can suffer an outage.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum BankingService {
    CARD_PAYMENTS,
    ONLINE_BANKING,
    MOBILE_APP,
    ATM_NETWORK,
    TRANSFERS
}
