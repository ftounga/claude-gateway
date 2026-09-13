package fr.claudegateway.radar;

/** Ce qui a lancé une synchro du Radar (F-100 / SF-100-02). */
public enum RadarSyncTrigger {

    /** Le créneau du soir, à l'heure. */
    SCHEDULED,

    /** « Synchroniser maintenant ». */
    MANUAL,

    /**
     * Le créneau du soir, rattrapé : le portable était fermé à l'heure prévue, la synchro est partie à la
     * connexion suivante. Le résumé du matin le <b>dit</b> (« synchro d'hier soir non faite, rattrapée à
     * 8 h 12 »).
     */
    CATCH_UP
}
