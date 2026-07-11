package fr.claudegateway.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Appel d'outil demandé par l'assistant (F-28), résolu depuis un bloc {@code tool_use}.
 *
 * @param id    identifiant de l'appel (à référencer dans le {@code tool_result})
 * @param name  nom de l'outil demandé
 * @param input arguments (objet JSON)
 */
public record AgentToolCall(String id, String name, JsonNode input) {
}
