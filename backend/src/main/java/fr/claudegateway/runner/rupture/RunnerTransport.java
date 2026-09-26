package fr.claudegateway.runner.rupture;

/**
 * Par quel canal le runner était relié (F-161 / SF-161-03).
 *
 * <p>Les deux transports <b>ne tombent pas pour les mêmes raisons</b> : un proxy d'entreprise coupe
 * les longs POST bien avant de fermer une socket. Confondre les deux dans le journal reviendrait à
 * mélanger deux populations et à ne rien pouvoir conclure.</p>
 */
public enum RunnerTransport {
    POLLING,
    WEBSOCKET
}
