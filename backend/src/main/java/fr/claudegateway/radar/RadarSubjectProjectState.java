package fr.claudegateway.radar;

/** Ce qu'il en est d'un lien sujet ↔ projet (F-106 / SF-106-06). */
public enum RadarSubjectProjectState {
    /** Le lien tient : déclaré, ou proposé puis confirmé. */
    CONFIRMED,
    /** Une question posée à l'utilisateur, sans effet tant qu'il n'a pas répondu. */
    PROPOSED,
    /** « Ce n'est pas ce projet » : retenu, jamais reproposé. */
    REFUSED
}
