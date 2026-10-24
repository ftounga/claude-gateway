package fr.claudegateway.videos;

/**
 * <b>Le statut d'un retour à la seconde</b> (F-214 / SF-214-01).
 *
 * <ul>
 * <li>{@code OUVERT} : posé, pas encore envoyé à l'agent ;</li>
 * <li>{@code ENVOYE} : envoyé (« Envoyer à l'agent » ou « Copier ») ;</li>
 * <li>{@code TRAITE} / {@code REJETE} : clos par l'agent à la republication, avec la version et un motif.</li>
 * </ul>
 */
public enum VideoCommentStatus {
    OUVERT,
    ENVOYE,
    TRAITE,
    REJETE;

    /** Vrai tant que l'agent ne l'a pas clos. */
    public boolean isPending() {
        return this == OUVERT || this == ENVOYE;
    }
}
