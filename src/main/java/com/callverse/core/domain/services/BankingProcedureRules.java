package com.callverse.core.domain.services;

import java.util.List;

/**
 * The bank's own rules, as the Quality Analyst checks them: the conceptual booklet's section 3.3
 * ("Les règles propres à la banque"). The backend owns them because the bank, not an agent, is the
 * authority on a rule; the judge receives them as the procedure a conversation must follow.
 */
public final class BankingProcedureRules {

    public static final List<String> ALL = List.of(
            "Le client doit être identifié avant toute information sur son compte.",
            "Une action sensible, comme l'opposition sur une carte, est proposée immédiatement quand elle s'impose.",
            "Un geste commercial au-delà du plafond du conseiller exige l'accord d'un superviseur ; "
                    + "il n'est jamais promis avant cet accord.",
            "Un soupçon de fraude suit la procédure obligatoire : sécuriser le compte ou la carte, puis escalader.",
            "Toute information donnée au client est exacte et justifiable par la base de connaissances.");

    private BankingProcedureRules() {}
}
