package fr.claudegateway.vigie;

/**
 * Les quatre vérifications de la mise en service guidée de la Vigie (F-122 / SF-122-02).
 *
 * <p>Elles sont vérifiées <b>avant</b> de « démarrer » : tant que les quatre ne sont pas au vert, on
 * ne démarre pas. La première est connue de la gateway (le battement du runner) ; les trois autres
 * viennent de l'instantané rapporté par le runner pour ce poste.</p>
 */
public enum VigieReadinessCheck {

    /** Le runner du poste a battu récemment (F-97). */
    RUNNER_CONNECTED,

    /** Le Chrome managé (SF-122-01) est lancé et joignable sur la boucle locale. */
    CHROME_REACHABLE,

    /** La session Teams est ouverte dans la fenêtre managée. */
    TEAMS_CONNECTED,

    /** Un test de lecture Teams de bout en bout a réussi (trouver une réunion → source réseau). */
    TEAMS_READ_TEST
}
