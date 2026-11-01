package fr.claudegateway.atelier.measure;

/**
 * <b>Où et pourquoi le ruban d'un tour cesse de prolonger celui du tour précédent</b> (F-229 /
 * SF-229-01). Des nombres et une cause fermée, jamais de contenu.
 *
 * @param index     premier bloc du ruban précédent qui n'est pas repris, {@code -1} s'il est prolongé
 *                  intégralement ; {@code null} sans ruban précédent ou sans ruban comparable
 * @param lostChars caractères du ruban précédent situés à partir de la divergence : ceux que le
 *                  fournisseur réécrira ; {@code null} quand {@code index} l'est
 * @param cause     la première cause qui s'applique ; {@code null} quand le ruban est prolongé et
 *                  qu'aucune édition de contexte ne l'a rendu caduc côté fournisseur
 */
public record RibbonDivergence(Integer index, Long lostChars, Cause cause) {

    /** Les causes, dans l'ordre où elles sont essayées. Valeurs fermées : jamais de texte libre en base. */
    public enum Cause {
        /** Le bloc rejoué est un résultat d'outil coupé au stockage. */
        RESULT_TRUNCATED,
        /** La trajectoire rangée a perdu ses étapes les plus anciennes (plafond du document). */
        TRACE_STEPS_DROPPED,
        /** Un raisonnement présent pendant le tour n'est pas rejoué. */
        REASONING_DROPPED,
        /** Le fournisseur a écarté des résultats d'outils pendant le tour précédent. */
        CLEARED_IN_TURN,
        /** Le résumé de tête a changé (compaction). */
        COMPACTION,
        /** La fenêtre des tours rejoués avec leur trajectoire a avancé d'un palier. */
        WINDOW_MOVED,
        /** Pas d'empreinte précédente lisible (premier tour du fil, ancienne ligne). */
        NO_PREVIOUS,
        /** Aucune des causes connues. */
        OTHER
    }

    /**
     * Ce que l'on sait du rejeu et du tour précédent, pour classer la divergence.
     *
     * @param turnStartBlock   index, dans le ruban courant, du premier bloc du tour précédent (sa
     *                         consigne) ; {@code -1} s'il n'est pas connu. Les faits du tour précédent
     *                         (étapes, raisonnement) n'expliquent qu'une divergence <b>après</b> ce bloc
     * @param stepsDropped     la trajectoire du tour précédent a perdu des étapes au rangement
     * @param reasoningDropped un raisonnement du tour précédent n'est pas rejoué
     * @param contextEdited    le fournisseur a écarté des résultats d'outils pendant le tour précédent
     * @param summaryPresent   le ruban courant commence par un résumé de compaction
     * @param windowMoved      la fenêtre de rejeu des trajectoires a bougé depuis le tour précédent
     */
    public record Signals(int turnStartBlock, boolean stepsDropped, boolean reasoningDropped,
            boolean contextEdited, boolean summaryPresent, boolean windowMoved) {

        /** Rien de connu : seules les causes lisibles dans le ruban lui-même s'appliquent. */
        public static Signals none() {
            return new Signals(-1, false, false, false, false, false);
        }
    }

    /** Sans ruban précédent lisible. */
    public static RibbonDivergence noPrevious() {
        return new RibbonDivergence(null, null, Cause.NO_PREVIOUS);
    }

    /**
     * Compare le ruban du premier appel d'un tour à celui du dernier appel du tour précédent.
     *
     * @param previous empreinte rangée du tour précédent du même fil, {@code null} s'il n'y en a pas
     * @param current  empreinte du ruban construit pour ce tour
     */
    public static RibbonDivergence compare(RibbonFingerprint previous, RibbonFingerprint current, Signals signals) {
        if (previous == null) {
            return noPrevious();
        }
        if (current == null || current.tooLarge()) {
            return new RibbonDivergence(null, null, Cause.OTHER);
        }
        Signals known = signals == null ? Signals.none() : signals;
        int index = current.divergenceFrom(previous);
        if (index < 0) {
            return new RibbonDivergence(-1, 0L, known.contextEdited() ? Cause.CLEARED_IN_TURN : null);
        }
        return new RibbonDivergence(index, previous.charsFrom(index), cause(index, current, known));
    }

    private static Cause cause(int index, RibbonFingerprint current, Signals signals) {
        boolean inPreviousTurn = signals.turnStartBlock() < 0 || index > signals.turnStartBlock();
        if (current.isTruncatedResult(index)) {
            return Cause.RESULT_TRUNCATED;
        }
        if (inPreviousTurn && signals.stepsDropped()) {
            return Cause.TRACE_STEPS_DROPPED;
        }
        if (inPreviousTurn && signals.reasoningDropped()) {
            return Cause.REASONING_DROPPED;
        }
        if (signals.contextEdited()) {
            return Cause.CLEARED_IN_TURN;
        }
        if (signals.summaryPresent() && index == 0) {
            return Cause.COMPACTION;
        }
        if (signals.windowMoved()) {
            return Cause.WINDOW_MOVED;
        }
        return Cause.OTHER;
    }
}
