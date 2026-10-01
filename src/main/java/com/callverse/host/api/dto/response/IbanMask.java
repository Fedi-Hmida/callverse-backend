package com.callverse.host.api.dto.response;

/**
 * Masks an IBAN for display: the country code and check digits, then the last four characters.
 *
 * <p>A host concern, not a domain one: the backend holds the full IBAN and each route decides how
 * much of it leaves. Four and four is what a customer or an advisor needs to say "the account ending
 * 0189" without the response becoming a source of full account numbers.
 *
 * <p>An IBAN too short to keep both ends without revealing most of it (fewer than 12 characters,
 * which no real IBAN is) is masked entirely rather than partly.
 */
public final class IbanMask {

    static final String MASKED = "****";

    private IbanMask() {}

    /** @return the masked form, or null for a null IBAN */
    public static String mask(String iban) {
        if (iban == null) {
            return null;
        }
        String compact = iban.replace(" ", "");
        if (compact.length() < 12) {
            return MASKED;
        }
        return compact.substring(0, 4) + " **** **** " + compact.substring(compact.length() - 4);
    }
}
