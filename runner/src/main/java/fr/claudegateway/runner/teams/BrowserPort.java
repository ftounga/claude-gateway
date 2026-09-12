package fr.claudegateway.runner.teams;

import java.util.function.Function;

/**
 * Où écouter le navigateur (F-87 / SF-87-02) : <b>toujours la boucle locale</b>, sur un port
 * résolu dans un ordre fixe.
 *
 * <p>L'hôte n'est pas configurable, et ce n'est pas un oubli : un port de débogage exposé sur le
 * réseau donne à qui l'atteint le contrôle d'un navigateur authentifié. Se rattacher à un navigateur
 * <b>distant</b> est donc refusé, quelle que soit la demande.</p>
 */
public final class BrowserPort {

    /** Le port par défaut du protocole de débogage, celui que tout le monde écrit. */
    public static final int DEFAULT_PORT = 9222;

    /** La seule adresse acceptée. */
    public static final String LOOPBACK = "127.0.0.1";

    /** Variable d'environnement, quand l'argument de ligne de commande n'est pas commode. */
    public static final String ENV = "CLAUDE_TEAMS_DEBUG_PORT";

    private BrowserPort() {
    }

    /**
     * Résout le port : argument, puis variable d'environnement, puis défaut. Une valeur illisible ou
     * hors bornes <b>ne fait pas échouer</b> le runner : elle retombe sur le défaut, et l'appelant
     * reçoit de quoi le dire.
     *
     * @param argument valeur de {@code --teams-port}, ou {@code null}
     * @param env      lecture d'environnement (injectée pour les tests)
     */
    public static int resolve(String argument, Function<String, String> env) {
        Integer fromArgument = parse(argument);
        if (fromArgument != null) {
            return fromArgument;
        }
        Integer fromEnv = parse(env == null ? null : env.apply(ENV));
        return fromEnv != null ? fromEnv : DEFAULT_PORT;
    }

    /** Vrai si la valeur donnée était exploitable : sert à avertir sans bloquer. */
    public static boolean isUsable(String value) {
        return value == null || value.isBlank() || parse(value) != null;
    }

    /**
     * L'adresse de découverte. <b>Refuse</b> tout ce qui n'est pas la boucle locale — la garde vit
     * ici, à l'unique endroit où l'adresse est fabriquée.
     */
    public static String discoveryUrl(String host, int port, String path) {
        if (host != null && !LOOPBACK.equals(host) && !"localhost".equals(host)) {
            throw new BrowserLinkException(BrowserLinkException.REMOTE_BROWSER_REFUSED,
                    "Le runner ne se rattache qu'au navigateur de cette machine (127.0.0.1). "
                            + "Un port de débogage ouvert sur le réseau donnerait le contrôle d'un "
                            + "navigateur authentifié à qui l'atteint.");
        }
        return "http://" + LOOPBACK + ":" + port + path;
    }

    private static Integer parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int port = Integer.parseInt(value.strip());
            return port >= 1024 && port <= 65_535 ? port : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
