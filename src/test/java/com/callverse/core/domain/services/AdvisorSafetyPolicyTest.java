package com.callverse.core.domain.services;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class AdvisorSafetyPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"Je veux un conseiller humain", "Je voudrais parler à une conseillère.",
            "Un conseiller svp", "I want a human advisor", "Je veux un conseiller. Je ne veux pas un crédit."})
    void recognizesExplicitHumanRequests(String message) {
        assertThat(AdvisorSafetyPolicy.requestsHuman(message)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Je ne veux pas de conseiller", "Pas besoin de conseiller humain",
            "Que fait un conseiller ?", "Je veux un crédit", "Bonjour"})
    void doesNotInventAHumanRequest(String message) {
        assertThat(AdvisorSafetyPolicy.requestsHuman(message)).isFalse();
    }
    @ParameterizedTest
    @ValueSource(strings = {"Je ne reconnais pas ce paiement.", "Je n’ai pas autorisé cette transaction.",
            "Ma carte a été volée.", "I don't recognize this charge.", "Je soupçonne une fraude."})
    void detectsPersonalReports(String message) {
        assertThat(AdvisorSafetyPolicy.reportsFraud(message)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Quelle est la procédure générale en cas de fraude ?",
            "Je ne soupçonne pas de fraude.", "Ma carte est refusée.", "Quel est mon solde ?"})
    void leavesOtherRequestsToTheAdvisor(String message) {
        assertThat(AdvisorSafetyPolicy.reportsFraud(message)).isFalse();
    }

    @Test
    void hidesInternalReferencesWithoutChangingAmounts() {
        assertThat(AdvisorSafetyPolicy.customerReply("Ticket 11111111-1111-1111-1111-111111111111 : 12 EUR"))
                .isEqualTo("Ticket [référence interne masquée] : 12 EUR");
    }
}
