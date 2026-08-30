package fr.claudegateway.runner.audit;

/**
 * Issue d'un appel d'outil telle qu'elle est journalisée (F-38 / SF-38-08, contrat de messages §9).
 * Liste close : un journal dont le vocabulaire varie ne se relit pas.
 */
public enum RunnerAuditOutcome {

    /** L'appel a abouti (un {@code exitCode} non nul reste un succès d'appel : la commande a tourné). */
    OK,
    /** L'appel a échoué (erreur du runner ou de la gateway). */
    ERROR,
    /** L'appel a été <b>refusé</b> par l'utilisateur, ou refusé avant émission. */
    DENIED,
    /** Le délai a expiré — côté runner ou côté gateway, ou faute de décision de validation. */
    TIMEOUT,
    /** L'appel a été interrompu (geste d'interruption, fermeture de session). */
    CANCELLED
}
