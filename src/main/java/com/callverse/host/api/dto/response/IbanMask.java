package com.callverse.host.api.dto.response;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * An IBAN inside free text: country code, check digits, then groups of four alphanumerics with
     * optional single spaces (the printed form) and a shorter last group; any case. Errs towards
     * masking: a word that happens to continue the pattern is masked too, which is the safe side.
     */
    private static final Pattern EMBEDDED = Pattern.compile(
            "\\b[A-Z]{2}[0-9]{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?\\b", Pattern.CASE_INSENSITIVE);

    private IbanMask() {}

    /** @return the masked form, or null for a null IBAN */
    public static String mask(String iban) {
        if (iban == null) {
            return null;
        }
        String compact = iban.replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        if (compact.length() < 12) {
            return MASKED;
        }
        return compact.substring(0, 4) + " **** **** " + compact.substring(compact.length() - 4);
    }

    /**
     * Masks every IBAN found inside free text, such as a transfer's counterparty
     * ({@code "Loyer DE89370400440532013000"}), and leaves the rest of the text untouched.
     *
     * @return the redacted text, or null for null
     */
    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        return EMBEDDED.matcher(text).replaceAll(match -> Matcher.quoteReplacement(mask(match.group())));
    }
}
