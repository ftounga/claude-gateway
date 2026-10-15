package fr.claudegateway.atelier.routine;

/**
 * <b>Le rythme d'une routine</b> (F-183 / SF-183-01, D1) : un choix fermé — pas de cron libre
 * (hors périmètre du cadrage).
 */
public enum RoutineCadence {

    /** Chaque jour, à l'heure dite. */
    DAILY,

    /** Du lundi au vendredi, à l'heure dite. */
    WEEKDAYS,

    /** Un jour de la semaine ({@code dayOfWeek}), à l'heure dite. */
    WEEKLY
}
