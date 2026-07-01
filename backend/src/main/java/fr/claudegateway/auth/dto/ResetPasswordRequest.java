package fr.claudegateway.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de l'application d'un nouveau mot de passe. Le mot de passe est plafonné à 72 caractères
 * (limite BCrypt), comme à l'inscription.
 */
public record ResetPasswordRequest(
        @NotBlank String token,
        @NotBlank @Size(min = 8, max = 72) String password) {
}
