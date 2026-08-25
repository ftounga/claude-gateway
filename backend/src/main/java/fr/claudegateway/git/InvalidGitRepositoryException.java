package fr.claudegateway.git;

/**
 * URL de dépôt refusée (F-31 / SF-31-02) : format non conforme, forge non supportée, ou dépôt hors de
 * portée du jeton de l'utilisateur.
 *
 * <p>GitHub répond {@code 404} aussi bien pour un dépôt inexistant que pour un dépôt privé auquel le
 * jeton n'a pas accès : les deux cas sont volontairement confondus ici, comme le fait GitHub — dire
 * « ce dépôt privé existe mais vous n'y avez pas accès » divulguerait son existence.</p>
 */
public class InvalidGitRepositoryException extends RuntimeException {

    public InvalidGitRepositoryException(String message) {
        super(message);
    }
}
