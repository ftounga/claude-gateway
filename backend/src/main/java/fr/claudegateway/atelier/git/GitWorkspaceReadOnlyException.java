package fr.claudegateway.atelier.git;

/**
 * Écriture refusée sur un workspace adossé à un dépôt (F-31 / SF-31-03).
 *
 * <p>Sur un projet Git, l'agent travaille sur le clone monté dans la sandbox ; le stockage objet ne
 * contient que les fichiers déjà rapatriés. Y écrire depuis l'explorateur créerait <b>deux vérités
 * divergentes</b> — l'une visible à l'écran, l'autre dans la sandbox — sans que rien ne le signale.
 * Le dépôt est la source ; les modifications passent par l'agent, puis par le push.</p>
 */
public class GitWorkspaceReadOnlyException extends RuntimeException {

    public GitWorkspaceReadOnlyException(String message) {
        super(message);
    }
}
