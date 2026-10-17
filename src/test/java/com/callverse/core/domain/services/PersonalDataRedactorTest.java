package com.callverse.core.domain.services;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What leaves the bank towards an external AI model: identifiers masked, the meaning kept. */
class PersonalDataRedactorTest {

    @Test
    @DisplayName("an IBAN, printed or compact, keeps its country code, check digits and last four")
    void iban() {
        assertThat(PersonalDataRedactor.redact("Mon IBAN est FR76 3000 6000 0112 3456 7890 189, merci"))
                .isEqualTo("Mon IBAN est FR76 **** **** 0189, merci");
        assertThat(PersonalDataRedactor.redact("virement vers DE89370400440532013000"))
                .isEqualTo("virement vers DE89 **** **** 3000");
    }

    @Test
    @DisplayName("a card number is masked only when it passes the Luhn check")
    void cardNumber() {
        assertThat(PersonalDataRedactor.redact("carte 4111 1111 1111 1111 refusée"))
                .isEqualTo("carte **** **** **** 1111 refusée");
        assertThat(PersonalDataRedactor.redact("ma carte 5555-5555-5555-4444"))
                .isEqualTo("ma carte **** **** **** 4444");
        assertThat(PersonalDataRedactor.redact("commande 1234567890123456"))
                .as("not a card number: fails Luhn").isEqualTo("commande 1234567890123456");
    }

    @Test
    @DisplayName("e-mail addresses and French phone numbers are replaced")
    void emailAndPhone() {
        assertThat(PersonalDataRedactor.redact("écrivez à amina.haddad@example.fr svp"))
                .isEqualTo("écrivez à [e-mail masqué] svp");
        assertThat(PersonalDataRedactor.redact("appelez le 06 12 34 56 78 ou +33 6 12 34 56 78"))
                .isEqualTo("appelez le [téléphone masqué] ou [téléphone masqué]");
    }

    @Test
    @DisplayName("amounts, dates and ordinary text are left untouched; null stays null")
    void untouched() {
        String text = "Paiement de 1 250,00 € le 12/10/2026, référence 4521, carte bloquée depuis ce matin.";
        assertThat(PersonalDataRedactor.redact(text)).isEqualTo(text);
        assertThat(PersonalDataRedactor.redact(null)).isNull();
    }
}
