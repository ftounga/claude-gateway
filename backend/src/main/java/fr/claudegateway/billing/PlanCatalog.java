package fr.claudegateway.billing;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Catalogue statique des plans proposés en V1 (F-09). Volontairement simple (PROJECT.md §14.2 —
 * Simplicity First) : une liste immuable en mémoire, sans table dédiée. Les prix et price IDs Stripe
 * sont externalisés en configuration (SF-09-02) ; ce catalogue ne décrit que la structure produit.
 *
 * <p>V1 expose les trois paliers Hosted (Solo/Pro/Daily). Les variantes BYOK seront ajoutées au
 * catalogue quand F-03 (BYOK) sera livrée — décision réversible (ajout d'entrées).</p>
 */
@Component
public class PlanCatalog {

    private static final List<Plan> PLANS = List.of(
            new Plan(PlanCode.SOLO, "Solo", ProviderMode.HOSTED, BillingPeriod.MONTHLY),
            new Plan(PlanCode.PRO, "Pro", ProviderMode.HOSTED, BillingPeriod.MONTHLY),
            new Plan(PlanCode.DAILY, "Pass journée", ProviderMode.HOSTED, BillingPeriod.DAILY));

    /** Liste immuable des plans du catalogue. */
    public List<Plan> plans() {
        return PLANS;
    }

    /** Vrai si le code correspond à un plan connu du catalogue. */
    public boolean contains(PlanCode code) {
        return PLANS.stream().anyMatch(p -> p.code() == code);
    }
}
