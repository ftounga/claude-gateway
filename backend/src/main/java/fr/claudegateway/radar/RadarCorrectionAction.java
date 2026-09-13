package fr.claudegateway.radar;

/**
 * Les corrections de l'utilisateur (F-99 / SF-99-02) — toutes souveraines (cadrage §4.2).
 */
public enum RadarCorrectionAction {

    // ------------------------------------------------------------------------------------ sujet

    /** Renommer : l'ancien nom devient un alias. */
    RENAME(Target.SUBJECT),

    /** Dire l'état d'un sujet (y compris le rouvrir). */
    SET_STATE(Target.SUBJECT),

    /** Dire la prochaine étape (vide = l'effacer). */
    SET_NEXT_STEP(Target.SUBJECT),

    /** Dire l'échéance (vide = l'effacer). */
    SET_DUE_DATE(Target.SUBJECT),

    /** Fusionner ce sujet dans un autre (SF-99-03 ; route dédiée). */
    MERGE(Target.SUBJECT),

    /** Séparer d'un sujet ce qui n'en est pas (SF-99-03 ; route dédiée). */
    SPLIT(Target.SUBJECT),

    /** L'utilisateur clôt : immédiat, souverain (SF-99-04 ; route dédiée). */
    CLOSE(Target.SUBJECT),

    /** L'utilisateur confirme une proposition de clôture (SF-99-04 ; route dédiée). */
    CONFIRM_CLOSE(Target.SUBJECT),

    /** L'utilisateur refuse une proposition de clôture (SF-99-04 ; route dédiée). */
    REJECT_CLOSE(Target.SUBJECT),

    /** L'utilisateur laisse clos un sujet qui s'est réveillé (SF-99-04 ; route dédiée). */
    DISMISS_WAKE(Target.SUBJECT),

    /** Un sujet né d'une nouvelle de l'utilisateur (F-104) ; l'annuler le supprime s'il n'a rien reçu d'autre. */
    CREATE_SUBJECT(Target.SUBJECT),

    /** Un alias dit par l'utilisateur (SF-99-06 ; route dédiée) : l'annuler le retire. */
    ADD_ALIAS(Target.SUBJECT),

    /** Un alias ou une consigne retirés par l'utilisateur (SF-99-06 ; route dédiée) : l'annuler les recrée. */
    REMOVE_ALIAS(Target.SUBJECT),

    // ------------------------------------------------------------------------------- engagement

    /** « Fait » : tenu. */
    DONE(Target.COMMITMENT),

    /** « Pas moi » : désavoué, sort des listes. */
    NOT_MINE(Target.COMMITMENT),

    /** « Reporter » : nouvelle échéance. */
    POSTPONE(Target.COMMITMENT),

    /** Abandonné. */
    ABANDON(Target.COMMITMENT),

    /** « C'est moi » : la question d'un engagement probable, tranchée. */
    CONFIRM(Target.COMMITMENT),

    /** Rouvrir un engagement. */
    REOPEN(Target.COMMITMENT),

    /** Un engagement dit par l'utilisateur (F-104) ; l'annuler le supprime s'il n'a pas été corrigé depuis. */
    ADD_COMMITMENT(Target.COMMITMENT);

    /** Ce sur quoi porte une correction. */
    public enum Target {
        SUBJECT,
        COMMITMENT
    }

    private final Target target;

    RadarCorrectionAction(Target target) {
        this.target = target;
    }

    public Target target() {
        return target;
    }
}
