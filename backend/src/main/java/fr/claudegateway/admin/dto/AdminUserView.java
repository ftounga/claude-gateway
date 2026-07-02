package fr.claudegateway.admin.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Vue agrégée d'un utilisateur pour la console admin (F-20) : identité, rôle, abonnement et
 * consommation totale de tokens. Aucune donnée sensible (clé/token) n'y figure.
 */
public record AdminUserView(
        UUID id,
        String email,
        String role,
        OffsetDateTime createdAt,
        String planCode,
        String subscriptionStatus,
        OffsetDateTime currentPeriodEnd,
        long totalTokens) {
}
