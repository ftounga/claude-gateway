package fr.claudegateway.vigie;

/**
 * L'état d'une vérification de la mise en service (F-122 / SF-122-02) : vert, rouge, ou en attente.
 *
 * <p>{@link #PENDING} n'est pas un échec : c'est « on ne sait pas encore » (aucun instantané récent du
 * runner). Il empêche de démarrer sans jamais faire passer une vérification pour réussie.</p>
 */
public enum VigieCheckStatus {

    /** Vérifiée, au vert. */
    OK,

    /** Vérifiée, au rouge. */
    KO,

    /** Pas encore connue (aucun instantané frais). Bloque le démarrage sans conclure. */
    PENDING
}
