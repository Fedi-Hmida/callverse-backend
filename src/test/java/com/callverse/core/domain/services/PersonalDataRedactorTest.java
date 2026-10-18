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
    @DisplayName("an IBAN written in RIB groups, with dashes or a space after the country code is masked")
    void ibanVariants() {
        assertThat(PersonalDataRedactor.redact("RIB FR76 30006 00001 12345678901 89 svp"))
                .isEqualTo("RIB FR76 **** **** 0189 svp");
        assertThat(PersonalDataRedactor.redact("FR76-3000-6000-0112-3456-7890-189"))
                .isEqualTo("FR76 **** **** 0189");
        assertThat(PersonalDataRedactor.redact("compte FR 76 3000 6000 0112 3456 7890 189"))
                .isEqualTo("compte FR76 **** **** 0189");
        assertThat(PersonalDataRedactor.redact("BE68 5390 0754 7034 svp"))
                .as("the word after a full last group is not swallowed").isEqualTo("BE68 **** **** 7034 svp");
        assertThat(PersonalDataRedactor.redact("IBAN FR76 3000 6000 0112 3456 7890 188 (faute de frappe)"))
                .as("a mistyped printed IBAN is still masked").isEqualTo("IBAN FR76 **** **** 0188 (faute de frappe)");
    }

    @Test
    @DisplayName("a card number next to another number is still found")
    void cardNextToAnotherNumber() {
        assertThat(PersonalDataRedactor.redact("carte 4111111111111111 123"))
                .isEqualTo("carte **** **** **** 1111 123");
        assertThat(PersonalDataRedactor.redact("le 12 4111 1111 1111 1111"))
                .isEqualTo("le 12 **** **** **** 1111");
    }

    @Test
    @DisplayName("an e-mail containing digits stays one e-mail; other French phone forms are masked")
    void emailBeforePhoneAndPhoneForms() {
        assertThat(PersonalDataRedactor.redact("jean.0612345678@example.fr"))
                .isEqualTo("[e-mail masqué]");
        assertThat(PersonalDataRedactor.redact("+33 (0)6 12 34 56 78 ou 0033 6 12 34 56 78"))
                .isEqualTo("[téléphone masqué] ou [téléphone masqué]");
    }

    @Test
    @DisplayName("amounts, dates and ordinary text are left untouched; null stays null")
    void untouched() {
        String text = "Paiement de 1 250,00 € le 12/10/2026, référence 4521, carte bloquée depuis ce matin.";
        assertThat(PersonalDataRedactor.redact(text)).isEqualTo(text);
        assertThat(PersonalDataRedactor.redact(null)).isNull();
    }
}
