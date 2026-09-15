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
     * Le détail d'un événement de calendrier (F-89 / SF-89-05) — vu par le relevé réel à l'étape
     * « récapitulatif » : {@code /api/mt/{région}/v2.0/me/calendars/events/iCalUId/{id}}, depuis
     * l'onglet. Lu défensivement comme une réunion.
     */
    CALENDAR_EVENT,

    /**
     * L'objet de collaboration d'une réunion — son récapitulatif (F-89 / SF-89-05, <b>recalé
     * SF-89-13</b>) : {@code /api/mcps/{région}/collab/readcollabobject/V2/…}. <b>Désormais lu</b> :
     * le relevé réel (SF-89-12) en a donné la forme, et l'adaptateur en tire l'emplacement de
     * l'enregistrement ({@code resources[].metadata} → {@code driveId}/{@code driveItemId}/
     * {@code threadId}) en lisant ses champs par leur nom — aucun autre champ ne franchit la couche.
     */
    MEETING_COLLAB_OBJECT,

    /**
     * Reconnue, et volontairement ignorée : ressources statiques, télémétrie, présence, images.
     * Les distinguer d'{@link #UNKNOWN} est ce qui empêche la sonde de santé de crier au loup.
     */
    IGNORED,

    /** Ni reconnue, ni identifiée comme sans intérêt : le corps n'est pas lu. */
    UNKNOWN
}
