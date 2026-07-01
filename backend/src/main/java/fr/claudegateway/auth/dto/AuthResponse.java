package fr.claudegateway.auth.dto;

import fr.claudegateway.auth.MeResponse;

/**
 * Réponse d'une connexion réussie : le JWT plateforme et la vue publique du compte.
 * Le token est de type {@code Bearer} et se présente dans l'en-tête {@code Authorization}.
 */
public record AuthResponse(String accessToken, String tokenType, MeResponse user) {

    public static AuthResponse bearer(String accessToken, MeResponse user) {
        return new AuthResponse(accessToken, "Bearer", user);
    }
}
