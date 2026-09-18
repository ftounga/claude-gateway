package fr.claudegateway.teams.meeting;

/**
 * L'état d'un artefact réunion (F-128 / SF-128-01).
 *
 * <ul>
 *   <li>{@link #RECORDING} — session de capture ouverte : l'onglet a été rejoint dans le Chrome managé.
 *       <b>Drapeau SF-128-01</b> : les octets média (audio onglet + micro) sont capturés à partir de
 *       SF-128-02 ; ici RECORDING signifie « capture demandée / onglet rejoint ».</li>
 *   <li>{@link #PAUSED} — capture suspendue par l'utilisateur.</li>
 *   <li>{@link #STOPPED} — capture terminée (arrêt explicite).</li>
 *   <li>{@link #FAILED} — la capture n'a pas pu démarrer/se poursuivre (échec nommé côté runner).</li>
 * </ul>
 */
public enum MeetingState {
    RECORDING,
    PAUSED,
    STOPPED,
    FAILED;

    /** Vrai tant que la capture est « vivante » : l'indicateur « capture en cours » reste visible. */
    public boolean isLive() {
        return this == RECORDING || this == PAUSED;
    }
}
