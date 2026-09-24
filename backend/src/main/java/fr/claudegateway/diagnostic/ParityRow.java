package fr.claudegateway.diagnostic;

/**
 * <b>Une ligne de parité</b> (F-156 / SF-156-04) : une capacité de référence, et ce que le produit
 * en fait — <b>présente ?</b> et <b>déclenchée ?</b>
 *
 * @param referenceId la référence
 * @param name        son nom lisible
 * @param gives       ce qu'elle apporte
 * @param state       l'état constaté
 * @param note        l'écart en une phrase, ou la raison de l'écart volontaire
 */
public record ParityRow(String referenceId, String name, String gives, State state, String note) {

    /** Les états de la parité. Quatre, parce que trois mentiraient. */
    public enum State {
        /** Portée et vue à l'œuvre : la parité est tenue. */
        TENUE,
        /** Portée, mais jamais déclenchée : <b>dormante</b> — un branchement, pas un développement. */
        DORMANTE,
        /** Le produit ne sait pas le faire : c'est une feature à créer. */
        ABSENTE,
        /** Écartée volontairement, avec sa raison : <b>jamais</b> un manque. */
        ECARTEE,
        /** Rien n'a été observé sur la période : on ne conclut pas. */
        NON_OBSERVEE
    }

    /** Vrai pour le seul cas qui appelle une feature. */
    public boolean isRealGap() {
        return state == State.ABSENTE;
    }
}
