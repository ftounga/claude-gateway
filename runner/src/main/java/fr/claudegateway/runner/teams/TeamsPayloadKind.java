package fr.claudegateway.runner.teams;

/**
 * Ce qu'une réponse observée sur le réseau <b>porte</b> (F-87 / SF-87-01).
 *
 * <p>C'est la première des trois choses que l'adaptateur sait de Teams (quelles URL portent quoi,
 * quels champs lire, comment paginer). Le reste du produit ne voit que cette énumération — jamais
 * une URL.</p>
 */
public enum TeamsPayloadKind {

    /** Une page de messages d'une conversation. */
    CONVERSATION_MESSAGES,

    /** La liste des conversations. */
    CONVERSATION_LIST,

    /** Le flux d'activité — c'est là que Teams calcule déjà « où l'on m'a mentionné ». */
    ACTIVITY_FEED,

    /** Des résultats de recherche — l'index de Teams, pas le nôtre. */
    SEARCH_RESULTS,

    /** Le détail d'une réunion. */
    MEETING_DETAILS,

    /** La transcription d'une réunion enregistrée. */
    MEETING_TRANSCRIPT,

    /** Une fiche de personne. */
    PROFILE,

    /**
     * Reconnue, et volontairement ignorée : ressources statiques, télémétrie, présence, images.
     * Les distinguer d'{@link #UNKNOWN} est ce qui empêche la sonde de santé de crier au loup.
     */
    IGNORED,

    /** Ni reconnue, ni identifiée comme sans intérêt : le corps n'est pas lu. */
    UNKNOWN
}
