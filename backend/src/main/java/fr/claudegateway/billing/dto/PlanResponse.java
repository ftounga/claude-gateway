package fr.claudegateway.billing.dto;

import fr.claudegateway.billing.Plan;

/**
 * Représentation d'un plan du catalogue exposée au client (F-09). N'expose ni prix ni price ID
 * Stripe (ceux-ci restent en configuration serveur).
 *
 * @param code         code stable du plan
 * @param label        libellé affichable
 * @param providerMode mode fournisseur (HOSTED/BYOK)
 * @param period       périodicité (MONTHLY/DAILY)
 */
public record PlanResponse(String code, String label, String providerMode, String period) {

    public static PlanResponse from(Plan plan) {
        return new PlanResponse(
                plan.code().name(),
                plan.label(),
                plan.providerMode().name(),
                plan.period().name());
    }
}
