package com.callverse.core.domain.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FrenchIbanTest {

    @Test
    @DisplayName("bank, branch and account give the RIB key and the IBAN check digits")
    void buildsAValidFrenchIban() {
        // The demo customer's IBAN, built by hand from the same parts.
        assertThat(FrenchIban.of("30006", "00001", "12345678901")).isEqualTo("FR7630006000011234567890189");
    }

    @Test
    @DisplayName("every IBAN it builds passes the ISO 13616 mod-97 check")
    void alwaysPassesMod97() {
        for (String account : new String[] {"00000000001", "98765432109", "55555555555", "10293847560"}) {
            String iban = FrenchIban.of("30004", "00002", account);
            assertThat(iban).hasSize(27).startsWith("FR");
            assertThat(FrenchIban.isValid(iban)).as(iban).isTrue();
        }
        assertThat(FrenchIban.isValid("FR7630006000011234567890188")).isFalse();
    }

    @Test
    @DisplayName("parts of the wrong length are refused")
    void refusesMalformedParts() {
        assertThatThrownBy(() -> FrenchIban.of("3000", "00001", "12345678901")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FrenchIban.of("30006", "00001", "123")).isInstanceOf(IllegalArgumentException.class);
    }
}
