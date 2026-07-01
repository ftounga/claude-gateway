package fr.claudegateway.chat.dto;

import java.util.List;

/**
 * Réponse de {@code GET /api/chat/models} : modèle par défaut et liste blanche sélectionnable.
 */
public record ModelsResponse(String defaultModel, List<String> models) {
}
