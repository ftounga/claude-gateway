package fr.claudegateway.runner.teams;

/**
 * L'état de la session Teams observée dans la fenêtre managée (F-122 / SF-122-03).
 *
 * <p>La Vigie tourne en arrière-plan ; la fenêtre managée ne surgit que lorsque cet état passe à
 * {@link #RELOGIN_REQUIRED}. Le runner ne se connecte jamais à la place de l'utilisateur : il
 * <b>constate</b> l'expiration et la <b>demande</b>.</p>
 */
public enum TeamsSessionState {

    /** Pas encore observée : on ne sait pas. */
    UNKNOWN,

    /** Teams répond normalement : la session est ouverte. */
    CONNECTED,

    /** Une identification est requise (redirection login, ou 401/403) : reconnexion à demander. */
    RELOGIN_REQUIRED
}
