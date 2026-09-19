package fr.claudegateway.teams.meeting;

/**
 * L'état d'un artefact réunion (F-128 / SF-128-01, étendu SF-128-16).
 *
 * <ul>
 *   <li>{@link #JOINED} — l'onglet a été rejoint dans le Chrome managé et l'utilisateur est
 *       (best-effort) <b>in-call</b>, mais <b>l'enregistrement n'a pas encore démarré</b>
 *       (SF-128-16). C'est le premier temps du flux « Rejoindre » → « Démarrer l'enregistrement » :
 *       la capture ne commence qu'une fois la réunion réellement en cours, pour que le capteur survive.</li>
 *   <li>{@link #RECORDING} — enregistrement en cours : la capture d'onglet a été démarrée (SF-128-02).</li>
 *   <li>{@link #PAUSED} — capture suspendue par l'utilisateur.</li>
 *   <li>{@link #STOPPED} — capture terminée (arrêt explicite).</li>
 *   <li>{@link #FAILED} — la capture n'a pas pu démarrer/se poursuivre (échec nommé côté runner).</li>
 * </ul>
 */
public enum MeetingState {
    JOINED,
    RECORDING,
    PAUSED,
    STOPPED,
    FAILED;

    /**
     * Vrai tant que la capture est « vivante » : l'indicateur « capture en cours » reste visible.
     * {@link #JOINED} n'est pas « live » (rejointe mais pas encore en enregistrement).
     */
    public boolean isLive() {
        return this == RECORDING || this == PAUSED;
    }
}
