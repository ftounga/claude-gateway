package fr.claudegateway.runner.teams;

import java.nio.file.Path;
import java.util.function.Function;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>Les réglages du Chrome managé</b> (F-122 / SF-122-01) : port, dossier de profil, système — avec
 * des <b>défauts sûrs</b>, tous surchargeables par configuration.
 *
 * <p>Une seule règle n'est <b>pas</b> surchargeable, et ce n'est pas un oubli : l'adresse de débogage
 * reste la boucle locale ({@link BrowserPort#LOOPBACK}). Un port de débogage exposé sur le réseau
 * donnerait le contrôle d'un navigateur authentifié à qui l'atteint — la garde vit dans
 * {@link BrowserPort} et n'est pas dupliquée ici.</p>
 *
 * @param port       port de débogage résolu ({@link BrowserPort})
 * @param profileDir dossier de profil managé, distinct du profil par défaut de l'utilisateur
 * @param system     système d'exploitation courant
 */
public record ManagedChromeSettings(int port, Path profileDir, OperatingSystem system) {

    /** Réglages pour le système courant, lus dans l'environnement réel. */
    public static ManagedChromeSettings resolve(String portArg, String profileArg,
            Function<String, String> env) {
        return resolve(portArg, profileArg, OperatingSystem.current(), env);
    }

    /**
     * Réglages pour un système donné (injecté pour les tests).
     *
     * @param portArg    valeur de {@code --teams-port}, ou {@code null}
     * @param profileArg dossier de profil explicite, ou {@code null} pour le défaut par OS
     * @param system     système d'exploitation
     * @param env        lecture d'environnement (injectée pour les tests)
     */
    static ManagedChromeSettings resolve(String portArg, String profileArg, OperatingSystem system,
            Function<String, String> env) {
        int port = BrowserPort.resolve(portArg, env);
        Path profile = (profileArg != null && !profileArg.isBlank())
                ? Path.of(profileArg.strip())
                : ChromePaths.profileDir(system, env);
        return new ManagedChromeSettings(port, profile, system);
    }
}
