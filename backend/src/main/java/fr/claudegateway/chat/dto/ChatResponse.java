package fr.claudegateway.chat.dto;

import java.util.UUID;

/**
 * Réponse de {@code POST /api/chat} : conversation ciblée, message assistant produit et modèle utilisé.
 */
public record ChatResponse(UUID conversationId, MessageResponse message, String model) {
}
