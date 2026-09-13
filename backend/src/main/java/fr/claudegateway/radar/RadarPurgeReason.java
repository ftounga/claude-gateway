package fr.claudegateway.radar;

/** Pourquoi le Radar d'un poste a été purgé (F-99 / SF-99-05). */
public enum RadarPurgeReason {

    /** La mission du poste est clôturée (F-60). */
    MISSION_CLOSED,

    /** Le poste est retiré de la Vigie (F-106). */
    VIGIE_REMOVED,

    /** L'utilisateur l'a demandé. */
    USER_REQUEST,

    /** Le poste a été supprimé — réservé au système. */
    HOST_DELETED
}
