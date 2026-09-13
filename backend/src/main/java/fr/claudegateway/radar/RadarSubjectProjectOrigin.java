package fr.claudegateway.radar;

/** D'où vient un lien sujet ↔ projet (F-106 / SF-106-06). */
public enum RadarSubjectProjectOrigin {
    /** Déclaré par l'utilisateur sur la page du sujet : souverain. */
    USER,
    /** Proposé par l'analyse, parce qu'un échange nomme le projet. */
    PROPOSED
}
