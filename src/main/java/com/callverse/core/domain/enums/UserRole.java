package com.callverse.core.domain.enums;

/**
 * Role carried by an {@code app_user}. Exactly one role per user: the schema deliberately has
 * no join table, because a second role per user would buy nothing at this scale. Names are
 * fixed by the API contract and stay English.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum UserRole {
    CUSTOMER,
    ADVISOR,
    SUPERVISOR,
    ADMIN
}
