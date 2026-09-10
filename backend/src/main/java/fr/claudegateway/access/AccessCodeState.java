package fr.claudegateway.access;

/**
 * État d'un code d'accès (F-62), <b>dérivé</b> de ses dates — jamais stocké.
 *
 * <p>Le stocker obligerait quelque chose à le mettre à jour au bon moment, c'est-à-dire à un job
 * planifié : exactement ce que F-62 refuse. Un état calculé est toujours juste, y compris après un
 * redémarrage, un déploiement ou une fenêtre de cron manquée.</p>
 */
public enum AccessCodeState {

    /** Émis, jamais consommé, encore valable : il attend d'être remis. */
    ISSUED,

    /** Consommé, et le droit est encore ouvert. */
    ACTIVE,

    /** Consommé, terme dépassé : le compte est revenu à ce qu'il était. */
    ENDED,

    /** Jamais consommé, date de validité dépassée : il ne vaut plus rien. */
    EXPIRED
}
