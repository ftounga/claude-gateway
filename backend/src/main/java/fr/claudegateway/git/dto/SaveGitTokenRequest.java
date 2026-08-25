package fr.claudegateway.git.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Requête d'enregistrement (ou de remplacement) du jeton GitHub. Le jeton n'est jamais journalisé
 * ni renvoyé en clair.
 *
 * @param token jeton d'accès personnel GitHub en clair (vérifié auprès de GitHub puis chiffré)
 */
public record SaveGitTokenRequest(
        @NotBlank
        @Size(max = 255)
        String token) {
}
