package fr.claudegateway.atelier.autonomy;

/** L'issue d'une course autonome (F-219 / SF-219-02). */
public enum AutonomyRunStatus {
    /** La boucle tourne. */
    RUNNING,
    /** Toutes les étapes sont vérifiées : la clôture est proposée. */
    DONE,
    /** Un vrai blocage : étape bloquée ou en échec, attente, information manquante, aucune avancée. */
    BLOCKED,
    /** Arrêtée : reprise en main par l'utilisateur, ou plafond de tours atteint. */
    STOPPED,
    /** Erreur : fournisseur, quota, poste perdu, course orpheline. */
    FAILED
}
