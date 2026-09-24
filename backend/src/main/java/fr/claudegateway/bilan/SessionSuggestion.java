package fr.claudegateway.bilan;

import java.math.BigDecimal;

/**
 * <b>Une suggestion d'usage</b> (F-155 / SF-155-02) : ce qui aurait mieux valu, sur l'un des trois
 * axes du PO.
 *
 * <p><b>Elle cite toujours sa mesure.</b> Sans mesure, une suggestion n'est qu'un avis — et un avis
 * ne se vérifie pas. C'est ce qui distingue ce bilan d'un conseil poli.</p>
 *
 * @param axis      l'axe concerné
 * @param advice    ce qu'il faut faire, en une phrase
 * @param measure   la mesure de la session d'où la suggestion sort, en toutes lettres
 * @param gainPct   le gain <b>calculé</b>, de 0 à 100
 * @param gainEur   le gain en euros quand l'axe est le coût, {@code null} sinon
 */
public record SessionSuggestion(
        Axis axis,
        String advice,
        String measure,
        int gainPct,
        BigDecimal gainEur) {

    /** Les trois axes du PO, et aucun autre. */
    public enum Axis {
        /** Ce que la session a payé. */
        COUT,
        /** Ce qu'elle a attendu. */
        TEMPS,
        /** La manière dont la demande a été posée et conduite. */
        RAISONNEMENT
    }

    static SessionSuggestion of(Axis axis, String advice, String measure, int gainPct) {
        return new SessionSuggestion(axis, advice, measure, gainPct, null);
    }

    static SessionSuggestion ofCost(String advice, String measure, int gainPct, BigDecimal gainEur) {
        return new SessionSuggestion(Axis.COUT, advice, measure, gainPct, gainEur);
    }
}
