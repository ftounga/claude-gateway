package fr.claudegateway.git;

/**
 * Nom de branche refusé (F-31 / SF-31-02 et SF-31-04) : forme invalide, ou branche de base du dépôt.
 *
 * <p>Pousser sur la branche par défaut est refusé par construction (ADR-015) : le travail d'un agent
 * arrive toujours sur une branche dédiée, que l'utilisateur relit avant de fusionner.</p>
 */
public class InvalidGitBranchException extends RuntimeException {

    public InvalidGitBranchException(String message) {
        super(message);
    }
}
