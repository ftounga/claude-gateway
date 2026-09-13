package fr.claudegateway.radar.analysis;

/** Où en est un lot d'échanges dans la file d'analyse (F-101 / SF-101-01). */
public enum RadarAnalysisBatchStatus {

    /** Reçu, en attente — ou en attente d'une nouvelle tentative. */
    PENDING,

    /** Pris par un travailleur. */
    PROCESSING,

    /** Analysé : les faits sont écrits, le texte brut est effacé. */
    DONE,

    /** Reporté sans échec : réserve épuisée, droit absent. Repris à l'échéance, tant qu'il n'a pas expiré. */
    DEFERRED,

    /** Abandonné après ses tentatives ; le brut reste jusqu'à l'expiration pour une reprise. */
    FAILED,

    /** Jamais analysé, et son texte a été effacé au terme de la rétention. */
    EXPIRED
}
