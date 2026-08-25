package fr.claudegateway.git;

/**
 * Levée lorsque GitHub est injoignable ou répond en erreur serveur au moment de vérifier un jeton.
 * Traduite en {@code 503 github_unavailable} : l'échec est <b>temporaire</b> et ne doit pas être
 * confondu avec un jeton refusé. Rien n'est persisté ; l'utilisateur peut réessayer.
 */
public class GitHubUnavailableException extends RuntimeException {

    public GitHubUnavailableException(String message) {
        super(message);
    }
}
