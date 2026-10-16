package fr.claudegateway.atelier.actions;

import java.util.Locale;

/**
 * <b>Qui doit agir</b> pour qu'une attente se lève (F-180 / SF-180-02, décision D1). Les gestes « à
 * moi » (se reconnecter en SSO, redémarrer le runner) ne sont pas des attentes envers autrui : les
 * mélanger faisait croire que le client bloquait ce que l'utilisateur pouvait débloquer seul.
 */
public enum TerminalActionQui {

    /** L'utilisateur lui-même : un geste à faire, personne à attendre. */
    MOI,
    /** Quelqu'un chez le client. */
    CLIENT,
    /** Un tiers : éditeur, fournisseur, support externe. */
    TIERS;

    /** La valeur lue d'un texte ; {@code null} si vide ; erreur lisible si inconnue. */
    public static TerminalActionQui parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidTerminalActionException("qui vaut MOI, CLIENT ou TIERS.");
        }
    }
}
