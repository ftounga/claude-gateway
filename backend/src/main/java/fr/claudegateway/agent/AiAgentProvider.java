package fr.claudegateway.agent;

/**
 * Abstraction d'un fournisseur d'agent à outils (F-28), parallèle d'{@code AIProvider} côté chat.
 * Le domaine ({@code AtelierChatService}) ne dépend que de cette interface, jamais d'Anthropic en
 * direct (Provider Independence). Chaque appel réalise <b>un seul</b> aller-retour ; la boucle
 * (exécution des outils + relance) est orchestrée par le domaine.
 */
public interface AiAgentProvider {

    /**
     * Exécute un tour : relaie {@code system} + {@code tools} + l'historique au fournisseur et renvoie
     * soit la réponse finale, soit les appels d'outils demandés.
     */
    AgentTurn nextTurn(AgentTurnRequest request);

    /**
     * Écouteur des <b>deltas de texte</b> d'un tour streamé (F-116). Neutre vis-à-vis du fournisseur :
     * le domaine y relaie chaque fragment vers son écran, sans que le paquet {@code agent} connaisse
     * l'Atelier (Provider Independence).
     */
    @FunctionalInterface
    interface AgentTextListener {

        /** Un fragment de texte de la réponse vient d'arriver. Jamais {@code null}. */
        void onTextDelta(String delta);

        /** Écouteur neutre : n'émet rien (mode non streamé). */
        AgentTextListener NONE = delta -> {
            // Aucun relais : le texte n'est découvert qu'à la fin du tour.
        };
    }

    /**
     * Exécute un tour en <b>streaming</b> (F-116 / SF-116-01) : consomme le flux du fournisseur, pousse
     * les deltas de texte dans {@code textListener} au fil de l'eau, puis reconstitue <b>le même</b>
     * {@link AgentTurn} qu'un appel non streamé (blocs {@code tool_use}, {@code usage}, raisonnement
     * signé, comptage cache identiques). Le corps de requête — donc le cache et le retry — reste
     * identique au chemin non streamé.
     *
     * <p>Par défaut, un fournisseur qui ne sait pas streamer retombe sur {@link #nextTurn(AgentTurnRequest)}
     * sans émettre de delta : additif, les implémentations existantes restent valides.</p>
     */
    default AgentTurn nextTurn(AgentTurnRequest request, AgentTextListener textListener) {
        return nextTurn(request);
    }
}
