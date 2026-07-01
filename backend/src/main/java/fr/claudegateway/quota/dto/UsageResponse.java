package fr.claudegateway.quota.dto;

import java.time.LocalDate;

import fr.claudegateway.quota.UsageSnapshot;

/**
 * Réponse de {@code GET /api/usage} (F-10) : consommation de tokens de l'utilisateur courant pour
 * la période de facturation en cours. Aucune donnée sensible (ni identifiant Stripe, ni clé).
 *
 * @param usedTokens      tokens consommés sur la période
 * @param quotaTokens     quota de tokens de la période (selon l'entitlement du plan/essai)
 * @param remainingTokens tokens restants (jamais négatif)
 * @param periodStart     premier jour de la période (mois calendaire UTC)
 * @param periodEnd       premier jour de la période suivante (borne exclusive)
 */
public record UsageResponse(
        long usedTokens,
        long quotaTokens,
        long remainingTokens,
        LocalDate periodStart,
        LocalDate periodEnd) {

    /** Projette un instantané métier en réponse d'API. */
    public static UsageResponse from(UsageSnapshot snapshot) {
        return new UsageResponse(
                snapshot.usedTokens(),
                snapshot.quotaTokens(),
                snapshot.remainingTokens(),
                snapshot.periodStart(),
                snapshot.periodEnd());
    }
}
