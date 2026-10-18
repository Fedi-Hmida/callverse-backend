package com.callverse.core.application.features.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContactSubjectTest {

    @Test
    @DisplayName("a known intent names the request in French")
    void knownIntentsHaveAFrenchLabel() {
        assertThat(ContactSubject.of("CARD", "Cartes")).isEqualTo("Carte bancaire");
        assertThat(ContactSubject.of("FRAUD", "Fraude")).isEqualTo("Suspicion de fraude");
        assertThat(ContactSubject.of("ACCOUNT_CLOSURE", "Comptes")).isEqualTo("Clôture de compte");
        assertThat(ContactSubject.of("BALANCE", "Comptes")).isEqualTo("Solde et opérations");
        assertThat(ContactSubject.of("CREDIT", "Crédit")).isEqualTo("Crédit");
    }

    @Test
    @DisplayName("no intent, OTHER or an unknown one falls back to the skill's label")
    void otherwiseTheSkillNamesTheRequest() {
        assertThat(ContactSubject.of(null, "Cartes")).isEqualTo("Cartes");
        assertThat(ContactSubject.of("OTHER", "Comptes")).isEqualTo("Comptes");
        assertThat(ContactSubject.of("SOMETHING_NEW", "Crédit")).isEqualTo("Crédit");
    }
}
