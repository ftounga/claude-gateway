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
 * @param mode      mode du tour (F-120 / SF-120-02) : {@link AgentTurnMode#ANSWER_PLAN} ou
 *                  {@link AgentTurnMode#ACT} ; {@code null} ⇒ {@link AgentTurnMode#ACT},
 *                  c'est-à-dire le comportement d'avant (panoplie complète)
 * @param shape     forme de l'appel (F-235 / SF-235-01) : plafond de sortie propre, outils du fournisseur,
 *                  outils stricts ; {@code null} ⇒ {@link AgentCallShape#standard()}, le comportement d'avant
 * @param previousResponseId identifiant de la réponse de l'appel précédent de la même conversation (F-276 /
 *                  SF-276-01), pour le diagnostic de cache du fournisseur ; {@code null} = aucun
 */
public record AgentTurnRequest(String model, String system, List<AgentMessage> messages,
        List<AgentTool> tools, String apiKey, AgentReasoning reasoning,
        AgentContextPolicy contextPolicy, AgentTurnMode mode, AgentCallShape shape, String previousResponseId) {

    public AgentTurnRequest {
        reasoning = reasoning == null ? AgentReasoning.none() : reasoning;
        contextPolicy = contextPolicy == null ? AgentContextPolicy.none() : contextPolicy;
        mode = mode == null ? AgentTurnMode.ACT : mode;
        shape = shape == null ? AgentCallShape.standard() : shape;
        previousResponseId = previousResponseId == null || previousResponseId.isBlank() ? null : previousResponseId;
    }

    /** Forme d'avant F-276 / SF-276-01 : aucun appel précédent connu. */
    public AgentTurnRequest(String model, String system, List<AgentMessage> messages,
            List<AgentTool> tools, String apiKey, AgentReasoning reasoning,
            AgentContextPolicy contextPolicy, AgentTurnMode mode, AgentCallShape shape) {
        this(model, system, messages, tools, apiKey, reasoning, contextPolicy, mode, shape, null);
    }

    /**
     * La même requête, chaînée à la réponse de l'appel précédent de la même conversation (F-276 /
     * SF-276-01) : le fournisseur peut alors dire où son cache a cassé. {@code null} = aucun.
     */
    public AgentTurnRequest withPreviousResponseId(String id) {
        return new AgentTurnRequest(model, system, messages, tools, apiKey, reasoning, contextPolicy, mode, shape, id);
    }

    /** Tour sans forme d'appel — forme conservée pour les appelants qui l'attendent (forme standard). */
    public AgentTurnRequest(String model, String system, List<AgentMessage> messages,
            List<AgentTool> tools, String apiKey, AgentReasoning reasoning,
            AgentContextPolicy contextPolicy, AgentTurnMode mode) {
        this(model, system, messages, tools, apiKey, reasoning, contextPolicy, mode, AgentCallShape.standard());
    }

    /** Tour sans mode — forme conservée pour les appelants qui l'attendent (défaut {@code ACT}). */
    public AgentTurnRequest(String model, String system, List<AgentMessage> messages,
            List<AgentTool> tools, String apiKey, AgentReasoning reasoning,
            AgentContextPolicy contextPolicy) {
        this(model, system, messages, tools, apiKey, reasoning, contextPolicy, AgentTurnMode.ACT);
    }

    /** Tour sans politique de contexte — forme conservée pour les appelants qui l'attendent. */
    public AgentTurnRequest(String model, String system, List<AgentMessage> messages,
            List<AgentTool> tools, String apiKey, AgentReasoning reasoning) {
        this(model, system, messages, tools, apiKey, reasoning, AgentContextPolicy.none(),
                AgentTurnMode.ACT);
    }

    /** Tour sans raisonnement — forme historique, conservée pour les appelants qui l'attendent. */
    public AgentTurnRequest(String model, String system, List<AgentMessage> messages,
            List<AgentTool> tools, String apiKey) {
        this(model, system, messages, tools, apiKey, AgentReasoning.none(), AgentContextPolicy.none(),
                AgentTurnMode.ACT);
    }
}
