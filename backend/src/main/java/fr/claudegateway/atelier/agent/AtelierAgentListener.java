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
     * Notifie une transition d'état de la session.
     *
     * @param state état atteint ({@code running} / {@code idle})
     */
    default void onStatus(String state) {
    }
}
