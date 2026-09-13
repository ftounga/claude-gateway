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
     * @param timeoutMs délai au bout duquel la demande expire (F-47 / SF-47-02), pour que l'écran
     *                  puisse afficher le temps restant plutôt que de laisser deux minutes
     *                  s'écouler en silence. {@code 0} quand le délai n'est pas connu — l'écran
     *                  n'affiche alors aucun compte à rebours
     */
    record AtelierConfirmRequest(String toolUseId, String tool, String detail, long timeoutMs) {
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

    /**
     * <b>Un bloc riche posé dans le fil</b> (F-89 / SF-89-02) : carte de réunion, moments, liste.
     *
     * <p>Relayé au fil de l'eau comme le plan, et pour la même raison : le bloc est le <b>travail
     * rendu</b>, pas un résumé de fin de tour — le voir arriver est ce qui fait qu'on n'attend pas
     * devant un écran muet pendant qu'un agent lit trente fils.</p>
     *
     * <p>Volontairement <b>par défaut neutre</b> : additif, le mode synchrone n'a personne à qui
     * relayer, et le bloc est de toute façon écrit dans la transcription du tour.</p>
     *
     * @param toolUseId identifiant de l'appel qui a posé le bloc — le même que celui du bloc de
     *                  transcription, pour que l'écran ne l'affiche pas deux fois
     * @param card      le bloc, déjà validé : l'écran ne valide rien
     */
    default void onCard(String toolUseId, fr.claudegateway.teams.block.TeamsBlockCard card) {
        // Aucun relais : le bloc reste dans la transcription du tour.
    }

    /**
     * <b>Un courriel vient d'être mis en file</b> (F-110 / SF-110-02) : le bloc « Courriel envoyé », relayé au
     * fil de l'eau. Par défaut neutre : le reçu est de toute façon écrit dans la transcription du tour.
     *
     * @param toolUseId identifiant de l'appel {@code email_me}
     * @param receipt   le reçu, jamais le corps
     */
    default void onEmail(String toolUseId, fr.claudegateway.mail.ClientMailReceipt receipt) {
        // Aucun relais : le reçu reste dans la transcription du tour.
    }

    /**
     * <b>Le poste de ce projet vient de refuser un appel : il est hors ligne</b> (F-97 / SF-97-02).
     *
     * <p>Sans cet événement, un {@code runner_unavailable} ne sortait de la boucle qu'en prose, dans
     * la réponse du modèle : l'écran l'apprenait par le texte et continuait d'afficher « connecté »
     * jusqu'au sondage suivant. Le relayer tel quel permet à <b>tout</b> l'écran — terminal, Forge,
     * supervision — de mettre ce poste à jour sur-le-champ.</p>
     *
     * <p>Volontairement <b>par défaut neutre</b> : additif, le mode synchrone n'a personne à qui
     * relayer.</p>
     *
     * @param hostId poste du projet, jamais {@code null}
     */
    default void onRunnerOffline(java.util.UUID hostId) {
        // Aucun relais : mode synchrone.
    }

    /**
     * Une précision déposée pendant le tour (F-84 / SF-84-06).
     *
     * @param steerId identifiant porté par les événements qui la concernent
     * @param text    le message de l'utilisateur
     */
    record AtelierSteer(String steerId, String text) {
    }

    /**
     * Les précisions déposées depuis la dernière étape, <b>prises</b> par la boucle au début de
     * l'étape suivante (F-84 / SF-84-06). Une précision rendue ici ne l'est qu'une fois.
     *
     * <p>Volontairement <b>par défaut vide</b> : le mode synchrone n'a pas de tour vivant, donc
     * personne pour préciser.</p>
     */
    default java.util.List<AtelierSteer> takeSteers() {
        return java.util.List.of();
    }

    /**
     * Une précision vient d'être ajoutée à la conversation et persistée : elle part au modèle à
     * l'étape {@code step} (à partir de 1).
     */
    default void onSteerApplied(AtelierSteer steer, int step) {
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
