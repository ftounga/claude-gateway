package fr.claudegateway.runner.teams;

/**
 * Le genre d'un message (F-87 / SF-87-01). Liste <b>close</b> : un genre qu'on ne sait pas
 * cartographier ne devient pas {@code TEXT} par défaut — le message n'est pas rendu et un manque
 * {@link TeamsGapKind#UNKNOWN_MESSAGE_KIND} le dit. C'est la règle « jamais à moitié faux ».
 */
public enum TeamsMessageKind {

    /** Texte simple. */
    TEXT,

    /** Texte mis en forme (le cas courant dans Teams). */
    RICH_TEXT,

    /** Événement de conversation : quelqu'un rejoint, quitte, renomme. */
    SYSTEM_EVENT,

    /** Événement de réunion : démarrage, fin, enregistrement, transcription. */
    MEETING_EVENT,

    /** Carte adaptative posée par une application. */
    CARD,

    /** Appel : début, fin, appel manqué. */
    CALL
}
