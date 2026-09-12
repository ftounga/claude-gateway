package fr.claudegateway.atelier.storage;

/**
 * Levée quand la suppression d'un préfixe de stockage n'a <b>pas</b> tout effacé (F-79 / SF-79-01).
 *
 * <p>Elle existe pour une raison précise : une suppression partielle rendue en {@code 500} muet est
 * <b>pire</b> que l'échec, parce qu'elle laisse croire que rien n'a bougé alors que des fichiers
 * sont partis. L'exception porte donc les deux nombres qui comptent — {@link #deletedCount()} et
 * {@link #remainingCount()} — et son message les cite.</p>
 *
 * <p>Elle ne porte <b>jamais</b> les clés en échec : celles-ci ne sortent que dans le journal
 * serveur. Un message d'erreur décrit une règle, pas une donnée ({@code CODING_RULES} §6).</p>
 */
public class WorkspaceStorageDeletionException extends RuntimeException {

    private final int deletedCount;
    private final int remainingCount;

    public WorkspaceStorageDeletionException(int deletedCount, int remainingCount, Throwable cause) {
        super(message(deletedCount, remainingCount), cause);
        this.deletedCount = deletedCount;
        this.remainingCount = remainingCount;
    }

    private static String message(int deletedCount, int remainingCount) {
        return "Suppression incomplète des fichiers du projet : " + deletedCount
                + " fichier(s) effacé(s), " + remainingCount + " restant(s). Le projet n'a pas été"
                + " supprimé — réessayez la suppression, seul le reliquat sera traité.";
    }

    /** Nombre de clés effectivement effacées avant l'arrêt. */
    public int deletedCount() {
        return deletedCount;
    }

    /** Nombre de clés encore présentes sous le préfixe. */
    public int remainingCount() {
        return remainingCount;
    }
}
