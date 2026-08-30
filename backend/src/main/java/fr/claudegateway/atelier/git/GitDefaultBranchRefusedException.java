package fr.claudegateway.atelier.git;

/**
 * Publication demandée sur la <b>branche par défaut</b> du projet (F-31 / SF-31-08).
 *
 * <p>Refusée sans appel de la moindre écriture : commiter directement sur {@code master} n'est pas
 * un cas d'usage de l'Atelier, c'est un accident. Rendue en {@code 409 git_default_branch_refused}.</p>
 */
public class GitDefaultBranchRefusedException extends RuntimeException {

    public GitDefaultBranchRefusedException(String message) {
        super(message);
    }
}
