package fr.claudegateway.agent;

/**
 * Mode d'un tour d'agent (F-120 / SF-120-02), à l'image du <i>plan mode</i> de Claude Code. Neutre
 * vis-à-vis du fournisseur : il ne décrit pas une capacité du provider mais l'<b>intention</b> du
 * tour, dont la boucle maison dérive la panoplie d'outils déclarée et la consigne système.
 *
 * <ul>
 *   <li>{@link #ACT} : comportement historique — panoplie complète (selon la cible et les volets sous
 *       licence), l'agent peut lire, écrire, exécuter. C'est le <b>défaut</b> : un tour sans mode se
 *       comporte comme avant SF-120-02.</li>
 *   <li>{@link #ANSWER_PLAN} : l'agent <b>répond</b> à la question ou <b>propose</b> un plan sans
 *       exécuter aucune mutation — seuls les outils de lecture / exploration / organisation sont
 *       déclarés (aucun {@code write_file}, {@code edit_file}, {@code bash} ni outil de volet).</li>
 * </ul>
 */
public enum AgentTurnMode {

    /** Répondre / proposer un plan, sans exécuter de mutation (outils mutants retirés). */
    ANSWER_PLAN,

    /** Agir : panoplie complète, comportement historique. Défaut quand le mode est absent. */
    ACT;
}
