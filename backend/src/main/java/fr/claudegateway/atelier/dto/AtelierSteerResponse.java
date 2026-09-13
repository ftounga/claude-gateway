package fr.claudegateway.atelier.dto;

import java.util.UUID;

/**
 * Réponse de {@code POST /api/workspaces/{id}/chat/steer} (F-84 / SF-84-06) : la précision est
 * déposée dans le tour vivant.
 *
 * @param steerId identifiant de la précision — celui que portent {@code steer_queued},
 *                {@code steer_applied} et {@code steer_followup} dans le flux du tour
 * @param turnId  tour qui l'a reçue
 */
public record AtelierSteerResponse(String steerId, UUID turnId) {
}
