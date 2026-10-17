package com.callverse.core.domain.services;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks personal identifiers in text that leaves the bank for an external AI model.
 *
 * <p>Data minimisation: the model needs to know that a customer gave an IBAN or a card number, not
 * the number itself. The database keeps the original; only what crosses the AI boundary is masked.
 * IBANs keep the same printed form the API shows staff ({@code FR76 **** **** 0189}), so the model
 * can still say "the account ending 0189".
 *
 * <p>Card numbers are masked only when they pass the Luhn check, so an order reference or an amount
 * of the same length is left alone. Errs towards masking otherwise: a false positive costs a word of
 * context, a false negative leaks an identifier.
 */
public final class PersonalDataRedactor {

    static final String EMAIL = "[e-mail masqué]";
    static final String PHONE = "[téléphone masqué]";

    /** Country code, check digits, groups of four with optional single spaces, a shorter last group. */
    private static final Pattern IBAN = Pattern.compile(
            "\\b[A-Z]{2}[0-9]{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?\\b", Pattern.CASE_INSENSITIVE);
    /** 13 to 19 digits, optionally grouped by single spaces or dashes. */
    private static final Pattern CARD = Pattern.compile("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)");
    /** French numbers: 0X or +33 X, then four pairs with optional separators. */
    private static final Pattern PHONE_FR =
            Pattern.compile("(?<![\\d+])(?:\\+33 ?|0)[1-9](?:[ .-]?\\d{2}){4}(?!\\d)");
    private static final Pattern EMAIL_ADDRESS =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private PersonalDataRedactor() {}

    /** @return the text with IBANs, card numbers, e-mail addresses and phone numbers masked; null for null */
    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        String redacted = IBAN.matcher(text).replaceAll(match -> Matcher.quoteReplacement(maskIban(match.group())));
        redacted = CARD.matcher(redacted).replaceAll(match -> Matcher.quoteReplacement(maskCard(match.group())));
        redacted = PHONE_FR.matcher(redacted).replaceAll(Matcher.quoteReplacement(PHONE));
        return EMAIL_ADDRESS.matcher(redacted).replaceAll(Matcher.quoteReplacement(EMAIL));
    }

    private static String maskIban(String iban) {
        String compact = iban.replace(" ", "").toUpperCase(Locale.ROOT);
        return compact.substring(0, 4) + " **** **** " + compact.substring(compact.length() - 4);
    }

    private static String maskCard(String candidate) {
        String digits = candidate.replaceAll("[ -]", "");
        if (!passesLuhn(digits)) {
            return candidate;
        }
        return "**** **** **** " + digits.substring(digits.length() - 4);
    }

    private static boolean passesLuhn(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
        }
        return sum % 10 == 0;
    }
}
