package fr.claudegateway.auth.dto;

/**
 * Réponse de {@code GET /api/auth/verify} : confirme la vérification et rappelle l'e-mail concerné.
 */
public record VerifyEmailResponse(boolean verified, String email) {
}
