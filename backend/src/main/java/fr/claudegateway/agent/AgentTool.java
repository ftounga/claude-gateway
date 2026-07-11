package fr.claudegateway.agent;

import java.util.Map;

/**
 * Définition d'un outil exposé à l'agent (F-28). Neutre : {@code inputSchema} est un JSON Schema
 * (objet) décrivant les paramètres. Le fournisseur le relaie tel quel.
 *
 * @param name        nom de l'outil (ex. {@code read_file})
 * @param description description à l'intention du modèle
 * @param inputSchema schéma JSON des paramètres (type object)
 */
public record AgentTool(String name, String description, Map<String, Object> inputSchema) {
}
