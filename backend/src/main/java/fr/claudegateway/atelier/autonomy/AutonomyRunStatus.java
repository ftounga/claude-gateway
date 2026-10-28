package fr.claudegateway.atelier.autonomy;

/** L'issue d'une course autonome (F-219 / SF-219-02). */
public enum AutonomyRunStatus {
    /** La boucle tourne. */
    RUNNING,
    /** Toutes les étapes sont vérifiées : la clôture est proposée. */
    DONE,
    /** Un vrai blocage : étape bloquée ou en échec, attente, information manquante, aucune avancée. */
    BLOCKED,
    /**
     * Avancée jusqu'au blocage (SF-219-05) : plus aucune étape à la portée de l'outil ; ce qui reste attend une
     * décision ou quelqu'un. Arrêt propre, distinct de l'échec.
     */
    PARTIAL,
    /** Arrêtée : reprise en main par l'utilisateur, ou plafond de tours atteint. */
    STOPPED,
    /** Erreur : fournisseur, quota, poste perdu, course orpheline. */
    FAILED
}
