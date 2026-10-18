package com.callverse.core.domain.services;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
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
 * <ul>
 *   <li><strong>IBAN</strong>: any grouping (fours, RIB groups, dashes, a space after the country
 *       code), confirmed by the ISO 13616 mod-97 check, which also finds where the IBAN ends so the
 *       next word is kept. A printed IBAN in groups of four is masked even when its check digits are
 *       wrong: a mistyped IBAN is still an IBAN.
 *   <li><strong>Card</strong>: one or more whole digit groups totalling 13 to 19 digits that pass
 *       the Luhn check, so "carte 4111 1111 1111 1111 123" masks the card and keeps the CVV-like
 *       number, while an order reference of the same length is left alone.
 *   <li><strong>E-mail</strong> first (it may contain digits), then <strong>French phones</strong>.
 * </ul>
 */
public final class PersonalDataRedactor {

    static final String EMAIL = "[e-mail masqué]";
    static final String PHONE = "[téléphone masqué]";

    private static final Pattern EMAIL_ADDRESS =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** A possible IBAN in any grouping; its true end is found by the mod-97 check. */
    private static final Pattern IBAN_CANDIDATE =
            Pattern.compile("\\b[A-Z]{2} ?[0-9]{2}(?:[ -]?[A-Z0-9]){11,30}", Pattern.CASE_INSENSITIVE);
    /** The printed form, masked even with wrong check digits: groups of four, a shorter last group. */
    private static final Pattern IBAN_PRINTED = Pattern.compile(
            "[A-Z]{2}[0-9]{2}(?: [A-Z0-9]{4}){2,7}(?: [A-Z0-9]{1,3})?\\b", Pattern.CASE_INSENSITIVE);
    /** Digit groups separated by single spaces or dashes. */
    private static final Pattern DIGIT_RUN = Pattern.compile("(?<![\\d])\\d+(?:[ -]\\d+)*(?![\\d])");
    /** French numbers: 0X, +33 X, 0033 X or +33 (0)X, then four pairs with optional separators. */
    private static final Pattern PHONE_FR = Pattern.compile(
            "(?<![\\d+])(?:(?:\\+|00)33 ?(?:\\(0\\) ?)?|0)[1-9](?:[ .-]?\\d{2}){4}(?!\\d)");

    private static final int IBAN_MIN = 15;
    private static final int IBAN_MAX = 34;
    private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

    private PersonalDataRedactor() {}

    /** @return the text with e-mails, IBANs, card numbers and phone numbers masked; null for null */
    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        String redacted = EMAIL_ADDRESS.matcher(text).replaceAll(Matcher.quoteReplacement(EMAIL));
        redacted = maskIbans(redacted);
        redacted = maskCards(redacted);
        return PHONE_FR.matcher(redacted).replaceAll(Matcher.quoteReplacement(PHONE));
    }

    // ------------------------------------------------------------------ IBAN

    private static String maskIbans(String text) {
        Matcher candidate = IBAN_CANDIDATE.matcher(text);
        StringBuilder out = new StringBuilder();
        int copied = 0;
        while (candidate.find()) {
            int end = validIbanEnd(text, candidate.start(), candidate.end());
            if (end < 0) {
                Matcher printed = IBAN_PRINTED.matcher(text).region(candidate.start(), candidate.end());
                if (!printed.lookingAt()) {
                    continue;
                }
                end = printed.end();
            }
            out.append(text, copied, candidate.start()).append(maskIban(text.substring(candidate.start(), end)));
            copied = end;
            if (end > candidate.end()) {
                candidate.region(end, text.length());
            }
        }
        return out.append(text.substring(copied)).toString();
    }

    /** The end of the longest prefix of {@code [start, end)} that is a valid IBAN, or -1. */
    private static int validIbanEnd(String text, int start, int end) {
        List<Integer> positions = new ArrayList<>();
        StringBuilder compact = new StringBuilder();
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                compact.append(Character.toUpperCase(c));
                positions.add(i);
            }
        }
        for (int length = Math.min(compact.length(), IBAN_MAX); length >= IBAN_MIN; length--) {
            if (passesMod97(compact.substring(0, length))) {
                return positions.get(length - 1) + 1;
            }
        }
        return -1;
    }

    private static boolean passesMod97(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                digits.append(c - 'A' + 10);
            } else {
                return false;
            }
        }
        return new BigInteger(digits.toString()).mod(NINETY_SEVEN).intValue() == 1;
    }

    private static String maskIban(String iban) {
        String compact = iban.replaceAll("[ -]", "").toUpperCase(Locale.ROOT);
        return compact.substring(0, 4) + " **** **** " + compact.substring(compact.length() - 4);
    }

    // ------------------------------------------------------------------ card

    private static String maskCards(String text) {
        return DIGIT_RUN.matcher(text).replaceAll(match -> Matcher.quoteReplacement(maskCardsInRun(match.group())));
    }

    /** Masks the longest run of whole groups, leftmost first, that is a Luhn-valid 13-19 digit card. */
    private static String maskCardsInRun(String run) {
        Matcher groupMatcher = Pattern.compile("\\d+").matcher(run);
        List<int[]> groups = new ArrayList<>();
        while (groupMatcher.find()) {
            groups.add(new int[] {groupMatcher.start(), groupMatcher.end()});
        }
        StringBuilder out = new StringBuilder();
        int copied = 0;
        int first = 0;
        while (first < groups.size()) {
            int matchedLast = -1;
            StringBuilder digits = new StringBuilder();
            for (int last = first; last < groups.size(); last++) {
                digits.append(run, groups.get(last)[0], groups.get(last)[1]);
                if (digits.length() > 19) {
                    break;
                }
                if (digits.length() >= 13 && passesLuhn(digits.toString())) {
                    matchedLast = last;
                }
            }
            if (matchedLast < 0) {
                first++;
                continue;
            }
            int from = groups.get(first)[0];
            int to = groups.get(matchedLast)[1];
            String card = run.substring(from, to).replaceAll("[ -]", "");
            out.append(run, copied, from).append("**** **** **** ").append(card.substring(card.length() - 4));
            copied = to;
            first = matchedLast + 1;
        }
        return out.append(run.substring(copied)).toString();
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
