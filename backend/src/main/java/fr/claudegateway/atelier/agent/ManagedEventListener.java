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
     * Variante portant l'identifiant de l'appel d'outil (F-30 SF-30-02), qui permet d'apparier la
     * commande à sa sortie. Délègue par défaut à {@link #onAction(String, String)} : une
     * implémentation qui n'a pas besoin de l'identifiant reste inchangée.
     *
     * @param toolUseId identifiant de l'appel, ou {@code null} si l'event ne le porte pas
     */
    default void onAction(String tool, String toolUseId, String detail) {
        onAction(tool, detail);
    }

    /**
     * Notifie la <b>sortie</b> d'un outil (event {@code agent.tool_result} / {@code agent.mcp_tool_result}),
     * c'est-à-dire ce que la commande a produit — indispensable au rendu terminal (F-30 / ADR-014).
     *
     * <p>La forme exacte de ces events n'est pas documentée : l'extraction est défensive et une forme
     * inattendue produit une sortie vide plutôt qu'une exception (le run ne doit jamais échouer à cause
     * de l'affichage). La sortie est déjà tronquée par le provider.</p>
     *
     * @param tool      nom de l'outil (ex. {@code bash})
     * @param toolUseId identifiant de l'appel d'outil correspondant, ou {@code null} si absent
     * @param output    sortie textuelle (jamais {@code null} ; éventuellement vide ou tronquée)
     * @param error     vrai si l'outil a échoué (code de retour non nul / {@code is_error})
     */
    default void onActionResult(String tool, String toolUseId, String output, boolean error) {
    }

    /**
     * Notifie une transition d'état de la session (event {@code session.status_running/idle}).
     *
     * @param state état atteint ({@code running} ou {@code idle})
     */
    default void onStatus(String state) {
    }
}
