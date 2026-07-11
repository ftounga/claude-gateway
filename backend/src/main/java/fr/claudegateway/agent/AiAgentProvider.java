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
}
