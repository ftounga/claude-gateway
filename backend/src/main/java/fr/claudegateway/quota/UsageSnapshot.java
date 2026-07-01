package fr.claudegateway.quota;

import java.time.LocalDate;

/**
 * Instantané de consommation d'un utilisateur pour la période courante (F-10). Objet métier interne
 * renvoyé par {@link QuotaService#currentUsage} et projeté en DTO par le controller.
 *
 * @param usedTokens      tokens déjà consommés sur la période
 * @param quotaTokens     quota de tokens de la période (selon l'entitlement)
 * @param remainingTokens tokens restants (jamais négatif)
 * @param periodStart     premier jour de la période (mois calendaire UTC)
 * @param periodEnd       premier jour de la période suivante (borne exclusive)
 */
public record UsageSnapshot(
        long usedTokens,
        long quotaTokens,
        long remainingTokens,
        LocalDate periodStart,
        LocalDate periodEnd) {
}
