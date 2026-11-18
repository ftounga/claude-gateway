package fr.claudegateway.jarvis.fastbrain;

/**
 * <b>La phase d'un travail de Jarvis</b> (F-270 / SF-270-01) — ce que l'écran montre d'un travail en fond : il réfléchit,
 * il exécute sur le poste, il attend votre « oui », il attend son tour (SF-270-04), ou il est fini (issue).
 */
public enum JarvisWorkPhase {
    /** Le tour Opus réfléchit (lecture, recherche). */
    THINKING,
    /** Le tour agit sur le poste (même règle que la marque de mutation : {@code bash}, écriture de fichier). */
    EXECUTING,
    /** La réponse finit par « Vous confirmez ? » : le travail attend un « oui ». */
    WAITING_YES,
    /** Enchaîné derrière le travail vivant de son fil (SF-270-04). */
    QUEUED,
    /** Fini avec une réponse. */
    DONE,
    /** Fini en échec. */
    FAILED,
    /** Interrompu (« arrête », reprise en main). */
    CANCELLED;

    /** Vrai pour une issue (le travail n'est plus vivant). */
    public boolean ended() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }

    /** La valeur lue en base, ou {@code fallback} si absente ou inconnue. */
    public static JarvisWorkPhase of(String value, JarvisWorkPhase fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.strip());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
