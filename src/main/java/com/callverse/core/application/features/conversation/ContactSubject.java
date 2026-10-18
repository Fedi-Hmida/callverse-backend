package com.callverse.core.application.features.conversation;

import java.util.Map;

/** What a contact is about, in the customer's words: the intent when known, else the skill's label. */
public final class ContactSubject {

    private static final Map<String, String> BY_INTENT = Map.of(
            "BALANCE", "Solde et opérations",
            "CARD", "Carte bancaire",
            "CREDIT", "Crédit",
            "FRAUD", "Suspicion de fraude",
            "ACCOUNT_CLOSURE", "Clôture de compte");

    private ContactSubject() {}

    public static String of(String intent, String skillLabel) {
        return intent == null ? skillLabel : BY_INTENT.getOrDefault(intent, skillLabel);
    }
}
