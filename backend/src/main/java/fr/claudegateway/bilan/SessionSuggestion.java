package fr.claudegateway.bilan;

import java.math.BigDecimal;

/**
 * <b>Une suggestion d'usage</b> (F-155 / SF-155-02) : ce qui aurait mieux valu, sur l'un des trois
 * axes du PO.
 *
 * <p><b>Elle cite toujours sa mesure.</b> Sans mesure, une suggestion n'est qu'un avis — et un avis
 * ne se vérifie pas. C'est ce qui distingue ce bilan d'un conseil poli.</p>
 *
 * @param kind      le <b>genre</b> du détecteur d'où elle sort — stable, contrairement au texte du
 *                  conseil : un texte qu'on reformule cesserait de se reconnaître d'un bilan à
 *                  l'autre, et un motif disparaîtrait à la première retouche de phrase
 *                  (F-155 / SF-155-05)
 * @param axis      l'axe concerné
 * @param advice    ce qu'il faut faire, en une phrase
 * @param measure   la mesure de la session d'où la suggestion sort, en toutes lettres
 * @param gainPct   le gain <b>calculé</b>, de 0 à 100
 * @param gainEur   le gain en euros quand l'axe est le coût, {@code null} sinon
 */
public record SessionSuggestion(
        Kind kind,
        Axis axis,
        String advice,
        String measure,
        int gainPct,
        BigDecimal gainEur) {

    /**
     * Le genre d'un détecteur (F-155 / SF-155-05). C'est lui qu'on compte d'un bilan à l'autre pour
     * reconnaître un <b>motif</b> — et un motif qui se répète n'est plus une habitude à corriger,
     * c'est le produit qui laisse le défaut se reproduire.
     */
    public enum Kind {
        /** La consigne système est repayée plein tarif à chaque tour. */
        CACHE_FROID,
        /** Un seul tour porte une part démesurée de la facture. */
        TOUR_HORS_NORME,
        /** L'attente vient presque entièrement d'un seul outil. */
        OUTIL_DOMINANT,
        /** La session a tourné en rond sur un même geste. */
        ECHECS_REPETES
    }

    /** Les trois axes du PO, et aucun autre. */
    public enum Axis {
        /** Ce que la session a payé. */
        COUT,
        /** Ce qu'elle a attendu. */
        TEMPS,
        /** La manière dont la demande a été posée et conduite. */
        RAISONNEMENT
    }

    static SessionSuggestion of(Kind kind, Axis axis, String advice, String measure, int gainPct) {
        return new SessionSuggestion(kind, axis, advice, measure, gainPct, null);
    }

    static SessionSuggestion ofCost(Kind kind, String advice, String measure, int gainPct,
                                    BigDecimal gainEur) {
        return new SessionSuggestion(kind, Axis.COUT, advice, measure, gainPct, gainEur);
    }
}
