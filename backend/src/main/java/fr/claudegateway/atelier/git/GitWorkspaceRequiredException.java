package fr.claudegateway.atelier.git;

/**
 * Opération réservée aux projets adossés à un dépôt (F-31 / SF-31-04), demandée sur un projet
 * d'archive : il n'y a pas de dépôt où publier.
 *
 * <p>Symétrique de {@link GitWorkspaceReadOnlyException} : chaque source de projet a ses gestes, et
 * les refus le disent plutôt que d'échouer plus loin, sans cause lisible.</p>
 */
public class GitWorkspaceRequiredException extends RuntimeException {

    public GitWorkspaceRequiredException(String message) {
        super(message);
    }
}
