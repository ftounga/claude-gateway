package fr.claudegateway.radar;

/** D'où vient un alias (F-99 / SF-99-03). */
public enum RadarAliasOrigin {

    /** Proposé par l'analyse. */
    SYNC,

    /** Dit par l'utilisateur (ajout à la main, ancien nom d'un renommage). */
    USER,

    /** Le nom d'un sujet absorbé par une fusion. */
    MERGE,

    /** Une consigne apprise d'une séparation (« ce nom n'est pas ce sujet »). */
    SPLIT
}
