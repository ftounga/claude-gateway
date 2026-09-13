package fr.claudegateway.radar;

/** D'où vient une preuve (F-99, cadrage §3). */
public enum RadarEvidenceSource {

    /** Un message d'une conversation ou d'un canal Teams. */
    TEAMS_MESSAGE,

    /** Une réunion Teams (transcription, récapitulatif), horodatée à la seconde. */
    TEAMS_MEETING,

    /** Un enregistrement hors Teams, transcrit sur la machine. */
    LOCAL_RECORDING,

    /** Une nouvelle donnée par l'utilisateur. */
    USER_NOTE,

    /** Un courriel collé par l'utilisateur. */
    PASTED_MAIL
}
