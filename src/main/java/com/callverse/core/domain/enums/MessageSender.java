package com.callverse.core.domain.enums;

/**
 * Origin of a message within a conversation transcript. SYSTEM covers messages the platform
 * emits itself, such as queue position or an escalation notice, which are neither customer
 * nor advisor speech.
 *
 * <p>Persisted as {@code EnumType.STRING}, never ORDINAL. These constants must match the
 * column's SQL CHECK constraint character for character.
 */
public enum MessageSender {
    CUSTOMER,
    ADVISOR,
    SYSTEM
}
