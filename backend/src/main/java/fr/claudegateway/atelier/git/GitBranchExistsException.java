package fr.claudegateway.atelier.git;

/**
 * Branche à créer déjà présente (F-31 / SF-31-10). Rendue en {@code 409 git_branch_exists} : on ne réécrit jamais une référence existante.
 */
public class GitBranchExistsException extends RuntimeException {

    public GitBranchExistsException(String message) {
        super(message);
    }
}
