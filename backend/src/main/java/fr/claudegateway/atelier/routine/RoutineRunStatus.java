package fr.claudegateway.atelier.routine;

/** L'état d'une exécution de routine (F-183 / SF-183-02). */
public enum RoutineRunStatus {

    /** Le tour tourne : ce que la vérification avant déploiement cherche (D5). */
    RUNNING,

    /** Le tour a fini normalement. */
    DONE,

    /** Le tour a été coupé net à son plafond : le travail fait est conservé (SF-183-03). */
    INCOMPLETE,

    /** Le tour a échoué ({@code error_code} dit pourquoi). */
    FAILED,

    /** Le tour a été interrompu par l'utilisateur. */
    INTERRUPTED,

    /** Le poste était hors ligne à l'heure : rattrapage à la reconnexion (D2). */
    MISSED_OFFLINE,

    /** Le budget du mois était épuisé : rien n'est parti (SF-183-03). */
    SKIPPED_BUDGET;

    /** Vrai si l'exécution est close. */
    public boolean finished() {
        return this != RUNNING;
    }
}
