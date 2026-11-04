package fr.claudegateway.auth;

import java.util.UUID;

/**
 * F-232 / SF-232-05 — ce que le filtre d'authentification demande pour une session « jarvis-compagnon », sans dépendre
 * du paquet Jarvis : la route est-elle dans la liste fermée de l'écran Jarvis, et le compagnon est-il encore appairé
 * (non révoqué) pour ce compte ?
 */
public interface CompanionSessionVerifier {

    /** Vrai si {@code method} + {@code path} (relatif au context-path, sans {@code /api}) est ouvert à la portée. */
    boolean allows(String method, String path);

    /** Vrai si le compagnon appartient à ce compte, est appairé et n'est pas révoqué. */
    boolean isActive(UUID userId, UUID companionId);
}
