package fr.claudegateway.radar;

/** Issue d'une synchro (F-99 / SF-99-01 ; enrichie par F-100). */
public enum RadarSyncStatus {

    /** En cours. */
    RUNNING,

    /** Terminée, tout ce qui devait être lu l'a été. */
    SUCCEEDED,

    /** Terminée, mais pas tout lu : le résumé le dira en tête (cadrage §4.4). */
    PARTIAL,

    /** Échouée. */
    FAILED,

    /** Annulée par l'utilisateur. */
    CANCELLED
}
