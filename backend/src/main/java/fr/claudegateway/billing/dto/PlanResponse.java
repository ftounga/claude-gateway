package fr.claudegateway.billing.dto;

import fr.claudegateway.billing.Plan;

/**
 * Représentation d'un plan du catalogue exposée au client (F-09 / SF-21-05). N'expose pas le price ID
 * Stripe (interne). Enrichie du quota mensuel de tokens et d'un montant d'affichage (EUR) pour la page
 * de facturation.
 *
 * @param code         code stable du plan
 * @param label        libellé affichable
 * @param providerMode mode fournisseur (HOSTED/BYOK)
 * @param period       périodicité (MONTHLY/DAILY)
 * @param tokens       allocation mensuelle de tokens du plan
 * @param priceEur     montant d'affichage en EUR (ex. {@code "24"}), ou {@code null} si non configuré
 */
public record PlanResponse(
        String code,
        String label,
        String providerMode,
        String period,
        long tokens,
        String priceEur) {

    public static PlanResponse of(Plan plan, long tokens, String priceEur) {
        return new PlanResponse(
                plan.code().name(),
                plan.label(),
                plan.providerMode().name(),
                plan.period().name(),
                tokens,
                priceEur);
    }
}
