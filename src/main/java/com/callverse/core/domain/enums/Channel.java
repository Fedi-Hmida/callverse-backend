package com.callverse.core.domain.enums;

/**
 * Medium a conversation takes place on. Only CHAT exists today; the column is sized for voice
 * and email to be added without a migration of the type itself.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum Channel {
    CHAT
}
