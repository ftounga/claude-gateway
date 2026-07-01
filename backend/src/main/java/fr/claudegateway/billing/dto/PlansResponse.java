package fr.claudegateway.billing.dto;

import java.util.List;

import fr.claudegateway.billing.Plan;

/**
 * Enveloppe de réponse du catalogue de plans (F-09).
 *
 * @param plans liste des plans proposés
 */
public record PlansResponse(List<PlanResponse> plans) {

    public static PlansResponse from(List<Plan> plans) {
        return new PlansResponse(plans.stream().map(PlanResponse::from).toList());
    }
}
