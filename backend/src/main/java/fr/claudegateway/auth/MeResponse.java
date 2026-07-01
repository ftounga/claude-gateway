package fr.claudegateway.auth;

import java.util.UUID;

import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRole;

/**
 * Vue publique de l'utilisateur courant renvoyée par {@code GET /api/me}.
 * N'expose jamais le hash de mot de passe ni aucune donnée sensible.
 */
public record MeResponse(
        UUID id,
        String email,
        boolean emailVerified,
        AuthProvider provider,
        UserRole role) {

    public static MeResponse from(User user) {
        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.isEmailVerified(),
                user.getProvider(),
                user.getRole());
    }
}
