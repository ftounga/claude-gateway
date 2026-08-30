package fr.claudegateway.atelier.git;

/**
 * Branche demandée introuvable sur le dépôt (F-31 / SF-31-10). Rendue en {@code 409 git_branch_unknown} : un projet ne doit jamais pointer une branche qui n'existe pas.
 */
public class GitBranchUnknownException extends RuntimeException {

    public GitBranchUnknownException(String message) {
        super(message);
    }
}
