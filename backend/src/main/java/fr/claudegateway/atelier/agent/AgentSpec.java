package fr.claudegateway.atelier.agent;

/**
 * Spécification d'un agent Managed Agents à créer (F-28 / Phase 2).
 *
 * @param name   nom lisible de l'agent
 * @param model  modèle utilisé par l'agent (ex. {@code claude-opus-4-8})
 * @param system prompt système de base de l'agent
 */
public record AgentSpec(String name, String model, String system) {
}
