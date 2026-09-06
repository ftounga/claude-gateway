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

    /**
     * Demande d'autorisation posée avant d'exécuter une action sur la machine de l'utilisateur
     * (F-38 / SF-38-08, décision D7). Le tour est <b>suspendu</b> tant que rien n'est tranché.
     *
     * <p>Volontairement <b>par défaut neutre</b> : additif, les implémentations antérieures (mode
     * synchrone, tests) restent valides. Un mode qui n'affiche pas la demande la verra refusée à
     * l'échéance — le silence ne vaut jamais autorisation.</p>
     */
    default void onConfirmRequest(AtelierConfirmRequest request) {
        // Aucun relais : la demande sera refusée à l'échéance.
    }

    /** Notifie la résolution d'une demande d'autorisation, pour que l'écran retire l'invite. */
    default void onConfirmResolved(AtelierConfirmResolved resolved) {
        // Aucun relais : mode synchrone.
    }

    /**
     * Demande d'autorisation relayée à l'écran (F-38 / SF-38-08).
     *
     * @param toolUseId identifiant de corrélation du contrat §1 — le même que celui de la trame
     *                  runner et de la ligne d'audit ; aucun second identifiant n'est créé
     * @param tool      outil concerné ({@code bash})
     * @param detail    ce qui est soumis à décision (la commande), tronqué pour l'affichage
     */
    record AtelierConfirmRequest(String toolUseId, String tool, String detail) {
    }

    /**
     * Résolution d'une demande d'autorisation (F-38 / SF-38-08).
     *
     * @param toolUseId identifiant de la demande tranchée
     * @param decision  {@code allow}, {@code deny} ou {@code timeout}
     */
    record AtelierConfirmResolved(String toolUseId, String decision) {
    }

    /**
     * Notifie un fragment de <b>sortie de commande</b> reçu du runner (F-38 / SF-38-07), au fil de
     * l'eau : c'est ce qui fait défiler {@code stdout}/{@code stderr} dans la session pendant qu'une
     * commande tourne, au lieu de tout découvrir à la fin.
     *
     * <p>Volontairement <b>par défaut neutre</b> : additif, les implémentations antérieures (mode
     * synchrone, tests) restent valides sans changement.</p>
     */
    default void onOutput(String chunk) {
        // Aucun relais : la sortie reste dans l'agrégat rendu au modèle.
    }

    /**
     * Notifie la consommation <b>cumulée</b> du tour après chaque itération (F-39 / SF-39-15).
     *
     * <p>C'est la moitié « visible » du lot 8 : jusqu'ici la boucle maison ne relayait aucune
     * consommation, si bien que la ligne vivante (acquis §4 n°5, SF-30-13) affichait des étapes et
     * une durée mais jamais de tokens — sur le moteur qui exécute réellement. Le compteur est celui
     * du quota, cache compris (SF-39-01).</p>
     *
     * <p>Volontairement <b>par défaut neutre</b> : additif, les implémentations antérieures (mode
     * synchrone, tests) restent valides sans changement.</p>
     *
     * @param tokens cumul des tokens traités depuis le début du tour (entrée + sortie)
     */
    /**
     * Plan de travail posé ou mis à jour par l'agent (F-39 / SF-39-13). Chaque appel porte le plan
     * <b>complet</b> : il remplace le précédent, il ne s'y ajoute pas.
     */
    default void onPlan(AtelierPlan plan) {
        // Par défaut, rien : le mode non streamé n'a personne à qui relayer.
    }

    default void onProgress(long tokens) {
        // Aucun relais : mode synchrone.
    }

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
