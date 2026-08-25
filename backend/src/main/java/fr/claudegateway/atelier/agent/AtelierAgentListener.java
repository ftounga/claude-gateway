package fr.claudegateway.atelier.agent;

/**
 * Miroir applicatif de {@link ManagedEventListener} pour la couche service/contrôleur (F-28 /
 * SF-28-10). Permet au contrôleur SSE de recevoir les étapes d'un run
 * ({@link AtelierSessionService#runTaskStreaming}) sans dépendre du type provider : le service fait
 * le pont vers {@link ManagedEventListener} en interne.
 *
 * <p>Méthodes {@code default} (no-op) : {@link #NOOP} redonne le comportement non-streamé
 * ({@link AtelierSessionService#runTask} délègue avec {@code NOOP}).</p>
 */
public interface AtelierAgentListener {

    /** Écouteur inerte : ne relaie rien (utilisé par le run non-streamé). */
    AtelierAgentListener NOOP = new AtelierAgentListener() {
    };

    /**
     * Notifie un fragment de texte produit par l'agent.
     *
     * @param text texte du fragment
     */
    default void onAgentText(String text) {
    }

    /**
     * Notifie l'usage d'un outil par l'agent.
     *
     * @param tool   nom de l'outil
     * @param detail courte description de l'action (ex. commande)
     */
    default void onAction(String tool, String detail) {
    }

    /**
     * Variante portant l'identifiant de l'appel d'outil (F-30 SF-30-02) ; délègue par défaut à
     * {@link #onAction(String, String)}, donc sans effet sur les implémentations existantes.
     */
    default void onAction(String tool, String toolUseId, String detail) {
        onAction(tool, detail);
    }

    /**
     * Variante portant en outre le fil d'exécution dont vient la commande (miroir applicatif de
     * {@link ManagedEventListener#onAction(String, String, String, String)}, F-35 / SF-35-02) ;
     * délègue par défaut, donc sans effet sur les implémentations existantes.
     *
     * @param threadId identifiant opaque du fil, ou {@code null} pour un run séquentiel
     */
    default void onAction(String tool, String toolUseId, String detail, String threadId) {
        onAction(tool, toolUseId, detail);
    }

    /**
     * Notifie une transition d'état de la session.
     *
     * @param state état atteint ({@code running} / {@code idle})
     */
    default void onStatus(String state) {
    }

    /**
     * Notifie la sortie d'un outil (miroir applicatif de
     * {@link ManagedEventListener#onActionResult(String, String, String, boolean)}, F-30 SF-30-01).
     *
     * @param tool      nom de l'outil
     * @param toolUseId identifiant de l'appel correspondant, ou {@code null}
     * @param output    sortie textuelle (jamais {@code null}, éventuellement tronquée)
     * @param error     vrai si l'outil a échoué
     */
    default void onActionResult(String tool, String toolUseId, String output, boolean error) {
    }

    /**
     * Variante portant en outre le fil d'exécution dont vient la sortie (miroir applicatif de
     * {@link ManagedEventListener#onActionResult(String, String, String, boolean, String)},
     * F-35 / SF-35-02).
     *
     * @param threadId identifiant opaque du fil, ou {@code null} pour un run séquentiel
     */
    default void onActionResult(String tool, String toolUseId, String output, boolean error,
            String threadId) {
        onActionResult(tool, toolUseId, output, error);
    }

    /**
     * Notifie que l'agent demande l'autorisation d'utiliser un outil (miroir applicatif de
     * {@link ManagedEventListener#onConfirmationRequest(String, String, String)}, F-33 / SF-33-02).
     *
     * @param tool           nom de l'outil demandé
     * @param confirmationId identifiant à renvoyer pour trancher
     * @param detail         commande que l'agent veut exécuter
     */
    default void onConfirmationRequest(String tool, String confirmationId, String detail) {
    }

    /**
     * Notifie qu'une demande d'autorisation a été tranchée (miroir applicatif de
     * {@link ManagedEventListener#onConfirmationResolved(String, String)}, F-33 / SF-33-02).
     *
     * @param confirmationId identifiant de la demande tranchée
     * @param decision       {@code allow}, {@code deny} ou {@code timeout}
     */
    default void onConfirmationResolved(String confirmationId, String decision) {
    }

}
