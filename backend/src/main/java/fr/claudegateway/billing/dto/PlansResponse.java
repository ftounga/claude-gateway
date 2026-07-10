package fr.claudegateway.billing.dto;

import java.util.List;

/**
 * Enveloppe de réponse du catalogue de plans (F-09 / SF-21-05). Les entrées sont enrichies (quota,
 * prix d'affichage) et filtrées aux plans réellement proposés (price configuré) côté controller.
 *
 * @param plans liste des plans proposés
 */
public record PlansResponse(List<PlanResponse> plans) {
}
