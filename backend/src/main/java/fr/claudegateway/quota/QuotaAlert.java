package fr.claudegateway.quota;

import java.time.LocalDate;

import fr.claudegateway.billing.TopUpPack;

/**
 * Alerte de quota d'un utilisateur pour la période courante (F-42). Objet métier interne construit
 * par {@link QuotaAlertService} et projeté en DTO par le controller.
 *
 * <p>{@code raised} vaut {@code true} uniquement si le seuil a été franchi <b>et</b> que
 * l'utilisateur n'a pas encore écarté l'alerte : c'est cette combinaison qui fait qu'une alerte
 * s'affiche une fois, pas trente.</p>
 *
 * @param raised           l'alerte est à présenter à l'utilisateur
 * @param usedTokens       tokens consommés sur la période
 * @param quotaTokens      quota effectif de la période (entitlement + tokens rachetés)
 * @param remainingTokens  tokens restants (jamais négatif)
 * @param usedPercent      part du quota consommée, en pourcentage entier arrondi
 * @param thresholdPercent seuil configuré, en pourcentage entier
 * @param periodEnd        premier jour de la période suivante (borne exclusive) — la date à laquelle
 *                         le quota repart
 * @param recommendedPack  pack de recharge proposé depuis l'alerte, ou {@code null} si le code
 *                         configuré est inconnu du catalogue
 */
public record QuotaAlert(
        boolean raised,
        long usedTokens,
        long quotaTokens,
        long remainingTokens,
        int usedPercent,
        int thresholdPercent,
        LocalDate periodEnd,
        TopUpPack recommendedPack) {
}
