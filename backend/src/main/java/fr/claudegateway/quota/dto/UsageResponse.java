package fr.claudegateway.quota.dto;

import java.time.LocalDate;

import fr.claudegateway.quota.UsageSnapshot;

/**
 * Réponse de {@code GET /api/usage} (F-10) : consommation de tokens de l'utilisateur courant pour
 * la période de facturation en cours. Aucune donnée sensible (ni identifiant Stripe, ni clé).
 *
 * @param usedTokens      tokens <b>facturés</b> sur la période : le décompte opposé au quota, où
 *                        chaque nature de token pèse son coût (F-63)
 * @param quotaTokens     quota de tokens de la période (selon l'entitlement du plan/essai)
 * @param remainingTokens tokens restants (jamais négatif)
 * @param processedTokens volume de tokens traités sur la période (entrée + sortie) — à ne pas
 *                        confondre avec le décompte ci-dessus
 * @param periodStart     premier jour de la période (mois calendaire UTC)
 * @param periodEnd       premier jour de la période suivante (borne exclusive)
 */
public record UsageResponse(
        long usedTokens,
        long quotaTokens,
        long remainingTokens,
        long processedTokens,
        LocalDate periodStart,
        LocalDate periodEnd) {

    /** Projette un instantané métier en réponse d'API. */
    public static UsageResponse from(UsageSnapshot snapshot) {
        return new UsageResponse(
                snapshot.usedTokens(),
                snapshot.quotaTokens(),
                snapshot.remainingTokens(),
                snapshot.processedTokens(),
                snapshot.periodStart(),
                snapshot.periodEnd());
    }
}
