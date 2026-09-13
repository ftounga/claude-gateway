package fr.claudegateway.atelier.live;

import java.util.UUID;

/**
 * Ce que rend le dépôt d'une <b>précision</b> dans un tour vivant (F-84 / SF-84-06).
 *
 * @param status  accepté, file pleine, ou tour fini/scellé
 * @param steerId identifiant de la précision acceptée ({@code null} sinon) — c'est lui que portent
 *                {@code steer_queued}, {@code steer_applied} et {@code steer_followup}
 * @param turnId  tour qui a reçu (ou refusé) la précision
 */
public record SteerReceipt(Status status, String steerId, UUID turnId) {

    /** Issue d'un dépôt. */
    public enum Status {
        /** Déposée : elle sera lue à l'étape suivante, ou ouvrira un tour de suite. */
        ACCEPTED,
        /** Trop de précisions attendent déjà : au-delà, c'est un nouveau tour qu'il faut. */
        FULL,
        /** Le tour est fini ou scellé : il ne lira plus rien. L'envoi ouvre un tour neuf. */
        ENDED
    }

    /** Vrai si la précision a été déposée. */
    public boolean accepted() {
        return status == Status.ACCEPTED;
    }
}
