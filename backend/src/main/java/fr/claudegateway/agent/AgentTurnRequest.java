package fr.claudegateway.agent;

import java.util.List;

/**
 * Requête d'UN tour d'agent (F-28) : un seul aller-retour avec le fournisseur, outils + consigne
 * système + historique. Neutre vis-à-vis du fournisseur.
 *
 * @param model     modèle cible
 * @param system    consigne système (peut être vide)
 * @param messages  historique ordonné (user/assistant, avec tool_use/tool_result)
 * @param tools     outils disponibles pour ce tour
 * @param apiKey    clé fournisseur pour cet appel (BYOK) ou {@code null} => clé plateforme
 * @param reasoning réglage de raisonnement du tour (F-39 / SF-39-10) ; {@code null} ⇒
 *                  {@link AgentReasoning#none()}, c'est-à-dire le comportement d'avant
 * @param contextPolicy politique de contexte du tour (F-39 / SF-39-12) ; {@code null} ⇒
 *                  {@link AgentContextPolicy#none()}, c'est-à-dire le comportement d'avant
 */
public record AgentTurnRequest(String model, String system, List<AgentMessage> messages,
        List<AgentTool> tools, String apiKey, AgentReasoning reasoning,
        AgentContextPolicy contextPolicy) {

    public AgentTurnRequest {
        reasoning = reasoning == null ? AgentReasoning.none() : reasoning;
        contextPolicy = contextPolicy == null ? AgentContextPolicy.none() : contextPolicy;
    }

    /** Tour sans politique de contexte — forme conservée pour les appelants qui l'attendent. */
    public AgentTurnRequest(String model, String system, List<AgentMessage> messages,
            List<AgentTool> tools, String apiKey, AgentReasoning reasoning) {
        this(model, system, messages, tools, apiKey, reasoning, AgentContextPolicy.none());
    }

    /** Tour sans raisonnement — forme historique, conservée pour les appelants qui l'attendent. */
    public AgentTurnRequest(String model, String system, List<AgentMessage> messages,
            List<AgentTool> tools, String apiKey) {
        this(model, system, messages, tools, apiKey, AgentReasoning.none(), AgentContextPolicy.none());
    }
}
