package com.callverse.core.domain.services;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic safeguards that do not depend on provider availability or instructions. */
public final class AdvisorSafetyPolicy {
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\\b");

    private AdvisorSafetyPolicy() {}

    public static boolean requestsHuman(String message) {
        String text = Normalizer.normalize(message, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace('’', '\'');
        if (text.matches("(?s).*\\b(?:ne veux pas|ne souhaite pas|pas besoin|don't want|do not want)\\b[^.!?]*"
                + "\\b(?:conseiller|conseillere|superviseur|humain|humaine|human|advisor|adviser)\\b.*")) return false;
        return text.matches("(?s).*\\b(?:je veux|je voudrais|je souhaite|j'aimerais|puis-je|i want|i would like|can i)\\b.*"
                + "\\b(?:conseiller|conseillere|superviseur|humain|humaine|human|advisor|adviser)\\b.*")
                || text.matches("(?s).*\\b(?:parler|transferez|transferer|mettez-moi|mettez moi)\\b.*"
                + "\\b(?:conseiller|conseillere|superviseur|humain|humaine)\\b.*")
                || text.matches("\\s*(?:un |une )?(?:conseiller|conseillere|humain|humaine)(?: humain| humaine)?(?: s'il vous plait| svp)?[.!?\\s]*");
    }

    public static boolean reportsFraud(String message) {
        String text = Normalizer.normalize(message, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace('’', '\'');
        return text.matches("(?s).*(?:je ne reconnais pas|je n'ai pas (?:autorise|effectue)|"
                + "i (?:do not|don't) recogni[sz]e|i did not authori[sz]e).*"
                + "(?:paiement|transaction|debit|payment|charge).*" )
                || text.matches("(?s).*(?:paiement|transaction|debit|payment|charge).*"
                + "(?:non (?:reconnu|autorise)|que je ne reconnais pas|unauthori[sz]ed|unrecogni[sz]ed).*" )
                || text.matches("(?s).*(?:je (?:soupconne|suspecte)|i suspect).*fraud.*")
                || text.matches("(?s).*(?:ma carte|mon compte|my card|my account).*"
                + "(?:vole|pirate|compromis|stolen|hacked|compromised).*" );
    }

    public static String customerReply(String reply) {
        return UUID_PATTERN.matcher(reply).replaceAll("[référence interne masquée]");
    }

    public static boolean claimsCardBlock(String reply) {
        String text = Normalizer.normalize(reply, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace('’', '\'');
        return text.matches("(?s).*(?:j'ai (?:bien )?bloque (?:votre|la) carte|"
                + "(?:votre|la) carte a (?:bien )?ete bloquee|i (?:have )?blocked (?:your|the) card|"
                + "your card has been blocked).*");
    }
}
