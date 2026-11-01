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
 * @param disabled vrai si le raisonnement doit être <b>éteint</b> (F-235 / SF-235-01, D2). Sur les modèles
 *                 récents, <b>omettre</b> le réglage veut dire « adaptatif » (le modèle raisonne) : l'omission
 *                 n'est donc pas « éteint ». La forme exacte (désactivé, ou raisonnement seulement entre outils
 *                 là où le modèle refuse la désactivation) est choisie par le fournisseur, jamais par le domaine
 */
public record AgentReasoning(boolean adaptive, String effort, boolean disabled) {

    private static final AgentReasoning NONE = new AgentReasoning(false, null, false);

    /** Forme historique : raisonnement adaptatif ou non, jamais éteint explicitement. */
    public AgentReasoning(boolean adaptive, String effort) {
        this(adaptive, effort, false);
    }

    /** Aucun raisonnement demandé : le tour part exactement comme avant cette subfeature. */
    public static AgentReasoning none() {
        return NONE;
    }

    /**
     * Raisonnement <b>éteint</b> (F-235 / SF-235-01, D2) — le cerveau rapide de Jarvis : un routage et une réponse
     * courte à partir d'un contexte fourni, où chaque dixième de seconde compte.
     *
     * @param effort niveau d'effort, ou {@code null} pour le défaut du fournisseur
     */
    public static AgentReasoning off(String effort) {
        return new AgentReasoning(false, effort, true);
    }
}
