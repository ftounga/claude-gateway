package fr.claudegateway.billing;

/**
 * Entrée du catalogue de plans (F-09). Décrit un plan proposé à l'utilisateur, indépendamment de
 * son prix : le montant et le price ID Stripe vivent dans la configuration (SF-09-02), jamais en dur
 * ici. Cette séparation garde le pricing réversible et hors du code (OQ-07).
 *
 * @param code         code stable du plan (clé de mapping vers Stripe)
 * @param label        libellé affichable
 * @param providerMode mode fournisseur (Hosted/BYOK)
 * @param period       périodicité de facturation
 */
public record Plan(
        PlanCode code,
        String label,
        ProviderMode providerMode,
        BillingPeriod period) {
}
