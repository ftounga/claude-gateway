package fr.claudegateway.quota.dto;

import java.time.LocalDate;

import fr.claudegateway.billing.TopUpPack;
import fr.claudegateway.billing.dto.TopUpPackResponse;
import fr.claudegateway.quota.QuotaAlert;

/**
 * Réponse de {@code GET /api/usage/alert} (F-42) : faut-il prévenir l'utilisateur qu'il approche de
 * sa limite, et avec quoi peut-il recharger ? Aucune donnée sensible (ni identifiant Stripe, ni clé).
 *
 * <p>Les pourcentages sont calculés et arrondis <b>côté serveur</b> : l'affichage ne recalcule rien
 * et ne peut donc pas diverger de la règle qui a levé l'alerte.</p>
 *
 * @param raised           l'alerte est à présenter (seuil franchi et alerte non écartée)
 * @param usedTokens       tokens consommés sur la période
 * @param quotaTokens      quota effectif de la période (abonnement + tokens rachetés)
 * @param remainingTokens  tokens restants (jamais négatif)
 * @param usedPercent      part du quota consommée, en pourcentage entier
 * @param thresholdPercent seuil configuré, en pourcentage entier
 * @param periodEnd        date à laquelle le quota repart (premier jour de la période suivante)
 * @param topUp            pack de recharge proposé en un clic, {@code null} si aucune alerte ou si
 *                         le code configuré est inconnu du catalogue
 */
public record QuotaAlertResponse(
        boolean raised,
        long usedTokens,
        long quotaTokens,
        long remainingTokens,
        int usedPercent,
        int thresholdPercent,
        LocalDate periodEnd,
        TopUpPackResponse topUp) {

    /** Projette une alerte métier en réponse d'API. */
    public static QuotaAlertResponse from(QuotaAlert alert) {
        TopUpPack pack = alert.recommendedPack();
        return new QuotaAlertResponse(
                alert.raised(),
                alert.usedTokens(),
                alert.quotaTokens(),
                alert.remainingTokens(),
                alert.usedPercent(),
                alert.thresholdPercent(),
                alert.periodEnd(),
                pack == null ? null : TopUpPackResponse.from(pack));
    }
}
