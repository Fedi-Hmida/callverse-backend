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

    @Test
    @DisplayName("masks an IBAN embedded in free text and leaves the rest of the text alone")
    void redactsEmbeddedIban() {
        assertThat(IbanMask.redact("Loyer DE89370400440532013000 octobre"))
                .isEqualTo("Loyer DE89 **** **** 3000 octobre");
    }

    @Test
    @DisplayName("text with no IBAN, and null, pass through unchanged")
    void redactLeavesOtherTextAlone() {
        assertThat(IbanMask.redact("CB MARKET 29/09")).isEqualTo("CB MARKET 29/09");
        assertThat(IbanMask.redact(null)).isNull();
    }

    @Test
    @DisplayName("masks an IBAN printed in groups of four, the way statements and the mask itself print it")
    void redactsGroupedIban() {
        assertThat(IbanMask.redact("VIR SEPA FR76 3000 6000 0112 3456 7890 189 LOYER"))
                .isEqualTo("VIR SEPA FR76 **** **** 0189 LOYER");
    }

    @Test
    @DisplayName("masks a lower-case IBAN too")
    void redactsLowerCaseIban() {
        assertThat(IbanMask.redact("de89370400440532013000")).isEqualTo("DE89 **** **** 3000");
    }
}
