package fr.claudegateway.radar;

/**
 * Certitude <b>en toutes lettres</b> (F-99, cadrage §4.3) — jamais un score.
 */
public enum RadarCertainty {

    /** « Je m'en charge », « je te l'envoie jeudi ». */
    CERTAIN,

    /** Tâche évoquée sans porteur, échéance déduite : présentée comme une question. */
    PROBABLE
}
