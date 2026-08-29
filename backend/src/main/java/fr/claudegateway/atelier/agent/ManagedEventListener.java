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
     * Variante portant en outre le <b>fil d'exécution</b> dont vient la commande (F-35 / SF-35-02).
     * Un run délégué mène plusieurs fils de front : sans ce marqueur, leurs commandes s'entrelacent
     * dans un flux unique où plus rien ne se lit. Délègue par défaut à
     * {@link #onAction(String, String, String)} — une implémentation qui n'en a pas besoin reste
     * inchangée.
     *
     * @param threadId identifiant opaque du fil, ou {@code null} si l'event ne le porte pas (cas de
     *                 tous les runs séquentiels)
     */
    default void onAction(String tool, String toolUseId, String detail, String threadId) {
        onAction(tool, toolUseId, detail);
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
     * Variante portant en outre le <b>fil d'exécution</b> dont vient la sortie (F-35 / SF-35-02).
     * Délègue par défaut à {@link #onActionResult(String, String, String, boolean)}.
     *
     * @param threadId identifiant opaque du fil, ou {@code null} si l'event ne le porte pas
     */
    default void onActionResult(String tool, String toolUseId, String output, boolean error,
            String threadId) {
        onActionResult(tool, toolUseId, output, error);
    }

    /**
     * Notifie une transition d'état de la session (event {@code session.status_running/idle}).
     *
     * @param state état atteint ({@code running} ou {@code idle})
     */
    /**
     * Battement de polling (F-30 / SF-30-13) : appelé une fois par tour de scrutation, que des events
     * soient arrivés ou non.
     *
     * <p>Ne porte aucune donnée : c'est un simple « je suis toujours là ». Ce que l'appelant en fait —
     * relever la consommation, rafraîchir un indicateur — ne regarde pas le provider, qui ignore tout
     * du quota et de l'écran.</p>
     */
    default void onPoll() {
    }

    default void onStatus(String state) {
    }

    /**
     * Notifie que l'agent <b>demande l'autorisation</b> d'utiliser un outil (F-33 / SF-33-02) : la
     * session est en pause tant qu'une réponse n'a pas été postée.
     *
     * @param tool           nom de l'outil demandé (en pratique {@code bash})
     * @param confirmationId identifiant à renvoyer pour trancher — c'est l'identifiant de
     *                       l'<b>event</b> ({@code sevt_…}), jamais le {@code tool_use_id} du bloc
     * @param detail         commande que l'agent veut exécuter, ou {@code ""} si absente
     */
    default void onConfirmationRequest(String tool, String confirmationId, String detail) {
    }

    /**
     * Notifie qu'une demande d'autorisation a été <b>tranchée</b> (F-33 / SF-33-02), que la décision
     * vienne de cette instance, d'une autre réplique, ou du refus automatique de fin de délai.
     *
     * @param confirmationId identifiant de la demande tranchée
     * @param decision       {@code allow}, {@code deny} ou {@code timeout} (refus automatique)
     */
    default void onConfirmationResolved(String confirmationId, String decision) {
    }

}
