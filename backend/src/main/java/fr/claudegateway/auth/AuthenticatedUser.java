package fr.claudegateway.auth;

import java.util.UUID;

import fr.claudegateway.user.UserRole;

/**
 * Identité authentifiée portée par le {@code SecurityContext} après validation du JWT.
 * Utilisée comme {@code principal} de l'{@code Authentication} Spring Security.
 *
 * <p>Volontairement minimale (pas de secret, pas de hash) : elle ne contient que ce qui
 * est nécessaire à l'autorisation et à l'isolation {@code user_id}.</p>
 */
public record AuthenticatedUser(UUID id, String email, UserRole role) {
}
