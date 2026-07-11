package fr.claudegateway.atelier.agent;

/**
 * Définition d'agent Managed Agents créée chez le fournisseur (F-28 / Phase 2).
 *
 * @param id      identifiant fournisseur de l'agent
 * @param version version de la définition d'agent (immuable ; réutilisée telle quelle)
 */
public record ManagedAgentDefinition(String id, String version) {
}
