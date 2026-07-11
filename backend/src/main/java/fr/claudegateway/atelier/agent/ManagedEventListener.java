package fr.claudegateway.atelier.agent;

/**
 * Écouteur des events de session Managed Agents relayés en direct pendant le polling (F-28 /
 * Phase 2, ADR-013). Permet à {@link ManagedAgentProvider#awaitCompletion(String, java.time.Duration,
 * int, ManagedEventListener)} de notifier chaque étape (texte de l'agent, usage d'outil, transition
 * d'état) au fur et à mesure, sans changer l'agrégation de la réponse finale.
 *
 * <p>Les méthodes sont {@code default} (no-op) : un appelant non-streamé passe {@link #NOOP} pour
 * conserver le comportement historique (zéro régression). Reste côté provider : le domaine applicatif
 * ne dépend jamais de cette interface directement (Provider Independence via un miroir applicatif).</p>
 */
public interface ManagedEventListener {

    /** Écouteur inerte : ne relaie rien (utilisé par la variante non-streamée). */
    ManagedEventListener NOOP = new ManagedEventListener() {
    };

    /**
     * Notifie un fragment de texte produit par l'agent (event {@code agent.message}).
     *
     * @param text texte du fragment (jamais {@code null})
     */
    default void onAgentText(String text) {
    }

    /**
     * Notifie l'usage d'un outil par l'agent (event {@code agent.tool_use} / {@code agent.custom_tool_use}).
     *
     * @param tool   nom de l'outil (ex. {@code bash})
     * @param detail courte description de l'action (ex. commande exécutée), ou {@code ""} si absente
     */
    default void onAction(String tool, String detail) {
    }

    /**
     * Notifie une transition d'état de la session (event {@code session.status_running/idle}).
     *
     * @param state état atteint ({@code running} ou {@code idle})
     */
    default void onStatus(String state) {
    }
}
