package fr.claudegateway.atelier.routine;

/** Ce qui a déclenché une exécution (F-183 / SF-183-02). */
public enum RoutineTrigger {

    /** À l'heure dite. */
    SCHEDULED,

    /** Rattrapage : le créneau a été manqué (poste hors ligne) ou dépassé de plus de la grâce. */
    CATCH_UP,

    /** « Lancer maintenant ». */
    MANUAL
}
