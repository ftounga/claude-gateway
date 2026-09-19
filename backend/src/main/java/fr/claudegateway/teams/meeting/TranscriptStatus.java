package fr.claudegateway.teams.meeting;

/**
 * L'état de la <b>transcription</b> d'une réunion (F-128 / SF-128-04).
 *
 * <ul>
 *   <li>{@link #NONE} — aucune transcription demandée (défaut).</li>
 *   <li>{@link #PENDING} — demandée, en attente du worker (STT configuré).</li>
 *   <li>{@link #TRANSCRIBING} — réclamée par le worker, appel STT en cours.</li>
 *   <li>{@link #TRANSCRIBED} — transcript rattaché à la réunion.</li>
 *   <li>{@link #FAILED} — l'appel STT a échoué (message nommé), réessayable.</li>
 * </ul>
 */
public enum TranscriptStatus {
    NONE,
    PENDING,
    TRANSCRIBING,
    TRANSCRIBED,
    FAILED;

    /** Vrai tant qu'une transcription est en cours (demandée ou en traitement). */
    public boolean isInFlight() {
        return this == PENDING || this == TRANSCRIBING;
    }
}
