package fr.claudegateway.runner.teams;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>Où est Chrome, et où ranger son profil dédié</b> (F-122 / SF-122-01).
 *
 * <p>La mise en service Teams était, jusqu'ici, entièrement manuelle : l'utilisateur devait connaître
 * le chemin de son navigateur et fabriquer une ligne de commande. Cette classe rend ce savoir au
 * runner — pour macOS, Windows et Linux — afin que {@link ManagedChrome} puisse lancer un Chrome
 * <b>dédié</b> tout seul, sans qu'aucune commande ne soit demandée.</p>
 *
 * <p><b>Un profil dédié, jamais celui de l'utilisateur.</b> Le dossier de profil managé est distinct
 * du profil par défaut : c'est une obligation (Chrome refuse le débogage sur le profil par défaut) et
 * une garantie (on ne touche jamais au navigateur personnel). Les chemins par défaut sont alignés sur
 * ceux qu'{@link BrowserLaunchAdvice} donnait déjà à la main.</p>
 *
 * <p>Le runner <b>dit où regarder</b> et lance ; il ne devine pas au-delà d'une liste close de
 * candidats. Chrome d'abord, puis les autres canaux Chromium (Chromium, Edge) en repli nommé — un
 * navigateur non-Chromium reste hors périmètre (cadrage §6).</p>
 */
public final class ChromePaths {

    /** Surcharge explicite du chemin du navigateur, quand la détection ne suffit pas. */
    public static final String PATH_ENV = "CLAUDE_TEAMS_CHROME_PATH";

    /** Surcharge explicite du dossier de profil managé. */
    public static final String PROFILE_ENV = "CLAUDE_TEAMS_CHROME_PROFILE";

    private ChromePaths() {
    }

    /**
     * L'exécutable du navigateur pour le système courant.
     *
     * <p>Ordre : une <b>surcharge</b> explicite ({@link #PATH_ENV}) est honorée telle quelle — un
     * réglage explicite prime sur la détection, même si le fichier n'est pas là (le diagnostic
     * « chemin introuvable » est du ressort de SF-122-04). Sinon, le premier candidat qui existe.</p>
     *
     * @param system système d'exploitation (injecté pour les tests)
     * @param env    lecture d'environnement (injectée pour les tests)
     * @param exists test d'existence d'un chemin (injecté pour les tests)
     * @return l'exécutable, ou {@link Optional#empty()} si aucun candidat n'existe
     */
    public static Optional<Path> executable(OperatingSystem system, Function<String, String> env,
            Predicate<Path> exists) {
        String override = env == null ? null : env.apply(PATH_ENV);
        if (override != null && !override.isBlank()) {
            return Optional.of(Path.of(override.strip()));
        }
        for (String candidate : candidates(system, env)) {
            Path path = Path.of(candidate);
            if (exists.test(path)) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    /**
     * Le dossier de profil managé pour le système courant. Une surcharge ({@link #PROFILE_ENV}) est
     * honorée ; sinon un dossier par OS, distinct du profil par défaut de l'utilisateur.
     */
    public static Path profileDir(OperatingSystem system, Function<String, String> env) {
        String override = env == null ? null : env.apply(PROFILE_ENV);
        if (override != null && !override.isBlank()) {
            return Path.of(override.strip());
        }
        switch (system) {
            case WINDOWS: {
                String base = firstNonBlank(read(env, "LOCALAPPDATA"), read(env, "USERPROFILE"),
                        "C:\\Users\\Default\\AppData\\Local");
                return Path.of(base, "ClaudeGateway", "chrome-teams");
            }
            case MACOS: {
                String home = firstNonBlank(read(env, "HOME"), System.getProperty("user.home"));
                return Path.of(home, "Library", "Application Support", "ClaudeGateway", "chrome-teams");
            }
            case LINUX: {
                String home = firstNonBlank(read(env, "HOME"), System.getProperty("user.home"));
                return Path.of(home, ".config", "claude-gateway", "chrome-teams");
            }
            case OTHER:
            default: {
                String home = firstNonBlank(read(env, "HOME"), System.getProperty("user.home"));
                return Path.of(home, ".claude-gateway", "chrome-teams");
            }
        }
    }

    /** Les chemins candidats pour le système donné, du plus probable au repli. */
    static List<String> candidates(OperatingSystem system, Function<String, String> env) {
        List<String> out = new ArrayList<>();
        switch (system) {
            case WINDOWS: {
                String pf = firstNonBlank(read(env, "ProgramFiles"), "C:\\Program Files");
                String pfx = firstNonBlank(read(env, "ProgramFiles(x86)"), "C:\\Program Files (x86)");
                String local = read(env, "LOCALAPPDATA");
                out.add(pf + "\\Google\\Chrome\\Application\\chrome.exe");
                out.add(pfx + "\\Google\\Chrome\\Application\\chrome.exe");
                if (local != null && !local.isBlank()) {
                    out.add(local + "\\Google\\Chrome\\Application\\chrome.exe");
                }
                out.add(pfx + "\\Microsoft\\Edge\\Application\\msedge.exe");
                out.add(pf + "\\Microsoft\\Edge\\Application\\msedge.exe");
                break;
            }
            case MACOS: {
                String home = firstNonBlank(read(env, "HOME"), System.getProperty("user.home"));
                out.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
                out.add(home + "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
                out.add("/Applications/Chromium.app/Contents/MacOS/Chromium");
                out.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
                break;
            }
            case LINUX: {
                out.add("/usr/bin/google-chrome");
                out.add("/usr/bin/google-chrome-stable");
                out.add("/opt/google/chrome/chrome");
                out.add("/usr/bin/chromium");
                out.add("/usr/bin/chromium-browser");
                out.add("/snap/bin/chromium");
                out.add("/usr/bin/microsoft-edge");
                out.add("/usr/bin/microsoft-edge-stable");
                break;
            }
            case OTHER:
            default: {
                out.add("/usr/bin/google-chrome");
                out.add("/usr/bin/chromium");
                out.add("/usr/bin/chromium-browser");
                break;
            }
        }
        return out;
    }

    private static String read(Function<String, String> env, String name) {
        return env == null ? null : env.apply(name);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
