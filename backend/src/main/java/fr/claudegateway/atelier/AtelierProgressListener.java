package fr.claudegateway.atelier;

/**
 * Observateur des étapes d'un tour d'atelier (F-28 / SF-28-05) : notifié <b>au fil de l'eau</b> des
 * actions fichier (lecture/écriture/liste/recherche) et du commentaire de l'assistant, afin de
 * relayer la progression en streaming (SSE) sans changer le résultat final.
 *
 * <p>Le mode synchrone existant ({@code chat}) passe un listener neutre ({@link #NOOP}) : la boucle
 * tool-use est strictement identique, seul le mode streaming exploite les notifications.</p>
 */
public interface AtelierProgressListener {

    /**
     * Étape d'action fichier de l'agent.
     *
     * @param type type d'action : {@code read}, {@code write}, {@code list} ou {@code search}
     * @param path chemin concerné (ou terme recherché) ; {@code null} pour {@code list}
     */
    record AtelierStepEvent(String type, String path) {
    }

    /** Notifie une action fichier (émise même si l'outil échoue ensuite : l'intention compte). */
    void onAction(AtelierStepEvent step);

    /** Notifie un commentaire textuel de l'assistant pour un tour (avant l'exécution de ses outils). */
    void onText(String text);

    /** Listener neutre : n'émet rien (mode synchrone historique). */
    AtelierProgressListener NOOP = new AtelierProgressListener() {
        @Override
        public void onAction(AtelierStepEvent step) {
            // Aucun relais : mode synchrone.
        }

        @Override
        public void onText(String text) {
            // Aucun relais : mode synchrone.
        }
    };
}
