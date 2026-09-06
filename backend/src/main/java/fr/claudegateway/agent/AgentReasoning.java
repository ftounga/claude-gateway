package fr.claudegateway.agent;

/**
 * Réglage de raisonnement d'un tour d'agent (F-39 / SF-39-10). Neutre vis-à-vis du fournisseur : il
 * dit <b>si</b> le modèle doit raisonner et <b>jusqu'où</b>, pas comment l'API l'exprime — le
 * mapping est confiné à {@code AnthropicAgentProvider} (Provider Independence).
 *
 * <p><b>Adaptatif, et non budgété</b> : c'est le modèle qui décide quand et combien raisonner. Un
 * budget de tokens fixe est une valeur qu'il faudrait deviner à l'avance, demande par demande ;
 * l'effort, lui, dit une intention.</p>
 *
 * @param adaptive vrai si le raisonnement adaptatif est demandé pour ce tour
 * @param effort   niveau d'effort ({@code low} à {@code max}), ou {@code null}/vide pour laisser le
 *                 fournisseur appliquer son défaut
 */
public record AgentReasoning(boolean adaptive, String effort) {

    private static final AgentReasoning NONE = new AgentReasoning(false, null);

    /** Aucun raisonnement demandé : le tour part exactement comme avant cette subfeature. */
    public static AgentReasoning none() {
        return NONE;
    }
}
