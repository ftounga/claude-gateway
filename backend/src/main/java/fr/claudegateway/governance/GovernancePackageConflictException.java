package fr.claudegateway.governance;

/**
 * Un geste refusé parce que l'état ne le permet pas (F-51 / SF-51-01) → 409 : un {@code slug} déjà
 * pris, ou la suppression d'un paquet encore publié.
 */
public class GovernancePackageConflictException extends RuntimeException {

    public GovernancePackageConflictException(String message) {
        super(message);
    }
}
