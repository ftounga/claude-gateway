package fr.claudegateway.agent;

import java.util.List;

/**
 * Requête d'UN tour d'agent (F-28) : un seul aller-retour avec le fournisseur, outils + consigne
 * système + historique. Neutre vis-à-vis du fournisseur.
 *
 * @param model    modèle cible
 * @param system   consigne système (peut être vide)
 * @param messages historique ordonné (user/assistant, avec tool_use/tool_result)
 * @param tools    outils disponibles pour ce tour
 * @param apiKey   clé fournisseur pour cet appel (BYOK) ou {@code null} => clé plateforme
 */
public record AgentTurnRequest(String model, String system, List<AgentMessage> messages,
        List<AgentTool> tools, String apiKey) {
}
