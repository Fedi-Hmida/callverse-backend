package com.callverse.core.domain.services;

import java.math.BigInteger;

/**
 * A French IBAN from its RIB parts: bank (5 digits), branch (5 digits), account (11 digits), plus
 * the RIB key and the ISO 13616 check digits computed here. Pure arithmetic, no I/O.
 */
public final class FrenchIban {

    private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

    private FrenchIban() {}

    public static String of(String bank, String branch, String account) {
        if (!bank.matches("\\d{5}") || !branch.matches("\\d{5}") || !account.matches("\\d{11}")) {
            throw new IllegalArgumentException("RIB parts are 5, 5 and 11 digits");
        }
        String rib = bank + branch + account;
        int ribKey = 97 - new BigInteger(rib).multiply(BigInteger.valueOf(100)).mod(NINETY_SEVEN).intValue();
        String bban = rib + String.format("%02d", ribKey);
        // "FR" is 1527 in the IBAN letter numbering; check digits are 98 minus the remainder of BBAN + FR00.
        int check = 98 - new BigInteger(bban + "152700").mod(NINETY_SEVEN).intValue();
        return "FR" + String.format("%02d", check) + bban;
    }

    public static boolean isValid(String iban) {
        if (iban == null || !iban.matches("FR\\d{25}")) {
            return false;
        }
        String rearranged = iban.substring(4) + "1527" + iban.substring(2, 4);
        return new BigInteger(rearranged).mod(NINETY_SEVEN).intValue() == 1;
    }
}
