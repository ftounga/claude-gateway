package fr.claudegateway.user;

import java.util.UUID;

/**
 * Levée quand aucun utilisateur ne correspond à l'identifiant demandé.
 * Traduite en réponse HTTP homogène par le {@code GlobalExceptionHandler}.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(UUID userId) {
        super("Utilisateur introuvable : " + userId);
    }
}
