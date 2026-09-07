package fr.claudegateway.billing.dto;

import fr.claudegateway.billing.Plan;

/**
 * Représentation d'un plan du catalogue exposée au client (F-09 / SF-21-05, étendue par F-43).
 * N'expose <b>aucun</b> price ID Stripe (interne). Enrichie du quota mensuel de tokens, d'un montant
 * d'affichage mensuel, et — depuis F-43 — de l'engagement annuel quand il est proposé.
 *
 * @param code            code stable du plan
 * @param label           libellé affichable
 * @param providerMode    mode fournisseur (HOSTED/BYOK)
 * @param period          périodicité <b>native</b> du plan (MONTHLY/DAILY)
 * @param tokens          allocation <b>mensuelle</b> de tokens du plan — inchangée par F-43 : un
 *                        engagement annuel n'alloue pas douze mois d'un coup, il reconduit la même
 *                        allocation mensuelle douze fois
 * @param priceEur        montant d'affichage mensuel en EUR (ex. {@code "24"}), ou {@code null}
 * @param yearlyPriceEur  montant d'affichage <b>annuel</b> en EUR (ex. {@code "240"}), ou
 *                        {@code null} si l'engagement annuel n'est pas proposé pour ce plan
 * @param yearlyAvailable vrai si ce plan est réellement souscriptible à l'année (price annuel
 *                        <b>et</b> montant d'affichage annuel configurés). L'écran lit ce booléen
 *                        plutôt que de déduire la disponibilité de la présence d'un prix : la
 *                        décision reste au serveur.
 */
public record PlanResponse(
        String code,
        String label,
        String providerMode,
        String period,
        long tokens,
        String priceEur,
        String yearlyPriceEur,
        boolean yearlyAvailable) {

    public static PlanResponse of(
            Plan plan, long tokens, String priceEur, String yearlyPriceEur, boolean yearlyAvailable) {
        return new PlanResponse(
                plan.code().name(),
                plan.label(),
                plan.providerMode().name(),
                plan.period().name(),
                tokens,
                priceEur,
                // Un montant annuel sans offre annuelle disponible serait un prix qu'on ne peut pas
                // payer : on ne l'expose pas du tout plutôt que de laisser l'écran arbitrer.
                yearlyAvailable ? yearlyPriceEur : null,
                yearlyAvailable);
    }
}
