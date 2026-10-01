package com.callverse.host.api.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The only thing standing between a stored IBAN and a response body. */
class IbanMaskTest {

    @Test
    @DisplayName("keeps the country code, the check digits and the last four characters")
    void masksTheMiddle() {
        assertThat(IbanMask.mask("FR7630006000011234567890189")).isEqualTo("FR76 **** **** 0189");
    }

    @Test
    @DisplayName("ignores the grouping spaces an IBAN is usually printed with")
    void ignoresSpaces() {
        assertThat(IbanMask.mask("FR76 3000 6000 0112 3456 7890 189")).isEqualTo("FR76 **** **** 0189");
    }

    @Test
    @DisplayName("masks entirely a value too short to keep both ends without revealing most of it")
    void masksShortValuesEntirely() {
        assertThat(IbanMask.mask("FR7612345")).isEqualTo(IbanMask.MASKED);
        assertThat(IbanMask.mask("")).isEqualTo(IbanMask.MASKED);
    }

    @Test
    @DisplayName("passes null through, so an absent value stays absent")
    void nullStaysNull() {
        assertThat(IbanMask.mask(null)).isNull();
    }
}
