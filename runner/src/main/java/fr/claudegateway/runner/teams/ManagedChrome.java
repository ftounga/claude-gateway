package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>Le runner lance et gère un Chrome dédié, tout seul</b> (F-122 / SF-122-01).
 *
 * <p>Jusqu'ici, faire lire Teams par la Vigie exigeait un parcours manuel : tuer Chrome, le relancer
 * avec {@code --remote-debugging-port} <b>et</b> {@code --user-data-dir}, vérifier le port, se
 * connecter. Invendable à un manager non technique. Cette classe rend ce parcours au runner : elle
 * <b>détecte</b> le navigateur, construit la ligne de commande, <b>lance</b> un Chrome dédié discret,
 * <b>vérifie</b> que le port répond, le <b>relance</b> s'il meurt et l'<b>arrête</b> proprement —
 * sans qu'aucune commande ne soit demandée à l'utilisateur.</p>
 *
 * <p><b>Deux invariants non négociables.</b></p>
 * <ol>
 *   <li><b>Un profil dédié</b> ({@code --user-data-dir}) : Chrome refuse le débogage sur le profil par
 *       défaut, et l'on ne touche jamais au navigateur personnel.</li>
 *   <li><b>Jamais {@code --headless}</b> : la connexion Teams (SSO + MFA d'entreprise) exige une vraie
 *       session interactive. Le Chrome managé est donc un Chrome <b>normal mais discret</b> — fenêtre
 *       positionnée hors champ — qui ne surgit que pour le login (SF-122-03).</li>
 * </ol>
 *
 * <p>La liaison d'observation ({@link BrowserLink} / {@link NetworkObserver}) se rattache ensuite à
 * <b>ce</b> Chrome managé, par l'attache CDP existante et inchangée.</p>
 *
 * <p><b>Éprouvable sans Chrome.</b> Le CI n'a pas de navigateur, et aucun compte Teams de test. Le
 * lancement passe par {@link ProcessSession} et la santé par une {@link Probe}, toutes deux injectées :
 * ce que le produit <b>décide</b> — quelle ligne de commande, quand lancer, quand relancer, quand
 * renoncer — s'éprouve sans qu'aucun binaire ne tourne.</p>
 */
public final class ManagedChrome {

    /** Où en est le Chrome managé après une tentative de mise en route. */
    public enum State {
        /** Le port répondait déjà : rien à lancer. */
        REACHABLE,
        /** Lancé par le runner, et le port a répondu dans le délai. */
        LAUNCHED,
        /** Lancé (ou déjà présent) mais le port n'a jamais répondu — voir SF-122-04 pour le pourquoi. */
        UNREACHABLE,
        /** Aucun exécutable de navigateur trouvé — voir SF-122-04 pour le message actionnable. */
        NO_BROWSER
    }

    /** Délai maximal d'attente que le port de débogage réponde après le lancement. */
    static final long PORT_WAIT_MS = 15_000L;

    /** Pas entre deux sondes du port. */
    static final long POLL_STEP_MS = 500L;

    /** Fenêtre poussée hors champ : présente (pas headless), mais invisible au quotidien. */
    static final String OFFSCREEN = "--window-position=-32000,-32000";

    /** La question « le port de débogage répond-il ? », injectée pour s'éprouver sans navigateur. */
    @FunctionalInterface
    public interface Probe {
        boolean reachable(int port);
    }

    private final Optional<Path> executable;
    private final Path profileDir;
    private final int port;
    private final ProcessSession session;
    private final Probe probe;
    private final BrowserLink.Sleeper sleeper;
    private final Consumer<String> say;

    private ProcessSession.Handle handle;

    public ManagedChrome(Optional<Path> executable, Path profileDir, int port,
            ProcessSession session, Probe probe, BrowserLink.Sleeper sleeper, Consumer<String> say) {
        this.executable = executable == null ? Optional.empty() : executable;
        this.profileDir = profileDir;
        this.port = port;
        this.session = session;
        this.probe = probe;
        this.sleeper = sleeper;
        this.say = say;
    }

    /**
     * Le Chrome managé pour le système courant, branché sur le lancement et la découverte réels.
     */
    public static ManagedChrome real(ManagedChromeSettings settings, Consumer<String> say) {
        Function<String, String> env = System::getenv;
        Optional<Path> exe = ChromePaths.executable(settings.system(), env, Files::exists);
        return new ManagedChrome(exe, settings.profileDir(), settings.port(), ProcessSession.real(),
                realProbe(), BrowserLink.realSleeper(), say);
    }

    /** La sonde réelle : {@code /json/version} sur la boucle locale répond et se déclare navigateur. */
    static Probe realProbe() {
        return port -> {
            try {
                String version = BrowserLink.httpGet(
                        BrowserPort.discoveryUrl(BrowserPort.LOOPBACK, port, "/json/version"));
                return version != null && version.contains("Browser");
            } catch (RuntimeException e) {
                return false;
            }
        };
    }

    /**
     * La ligne de commande exacte du Chrome managé. Chaque argument est un élément — jamais une ligne
     * shell à découper (un chemin de profil avec un espace ne peut pas devenir une commande).
     *
     * @throws java.util.NoSuchElementException si aucun exécutable n'a été résolu
     */
    List<String> commandLine() {
        return List.of(
                executable.orElseThrow().toString(),
                "--remote-debugging-port=" + port,
                "--remote-debugging-address=" + BrowserPort.LOOPBACK,
                "--user-data-dir=" + profileDir,
                OFFSCREEN,
                "--no-first-run",
                "--no-default-browser-check",
                BrowserLaunchAdvice.TEAMS_URL);
    }

    /** Vrai si le port de débogage répond en ce moment. */
    public boolean isReachable() {
        return probe.reachable(port);
    }

    /** Le chemin de l'exécutable résolu, pour le diagnostic ; vide si aucun n'a été trouvé. */
    public Optional<Path> executable() {
        return executable;
    }

    /**
     * S'assure qu'un Chrome managé joignable existe : le lance si le port ne répond pas encore.
     *
     * @return l'état atteint ({@link State})
     */
    public synchronized State ensureRunning() {
        if (probe.reachable(port)) {
            return State.REACHABLE;
        }
        if (executable.isEmpty()) {
            return State.NO_BROWSER;
        }
        return launchAndWait();
    }

    /**
     * Relance le Chrome managé s'il ne répond plus (fenêtre fermée, processus mort). Sans effet si le
     * port répond déjà.
     *
     * @return l'état atteint ({@link State})
     */
    public synchronized State relaunchIfDead() {
        if (probe.reachable(port)) {
            return State.REACHABLE;
        }
        if (executable.isEmpty()) {
            return State.NO_BROWSER;
        }
        if (handle != null && !handle.alive()) {
            handle = null;
        }
        return launchAndWait();
    }

    /** Arrête le Chrome managé. Le profil (et donc la session Teams) persiste sur le disque. */
    public synchronized void stop() {
        if (handle != null) {
            handle.destroy();
            handle = null;
        }
    }

    private State launchAndWait() {
        try {
            Files.createDirectories(profileDir);
            handle = session.start(commandLine(), null);
        } catch (IOException e) {
            // Le lancement n'a pas pu se faire du tout : le message nommé est du ressort de SF-122-04.
            return State.UNREACHABLE;
        }
        long waited = 0L;
        while (waited < PORT_WAIT_MS) {
            if (probe.reachable(port)) {
                announceReady();
                return State.LAUNCHED;
            }
            if (sleeper != null) {
                sleeper.sleep(POLL_STEP_MS);
            }
            waited += POLL_STEP_MS;
        }
        return State.UNREACHABLE;
    }

    private void announceReady() {
        if (say != null) {
            say.accept("Chrome dédié lancé et joignable sur " + BrowserPort.LOOPBACK + ":" + port
                    + " — fenêtre discrète, profil géré par le runner. Connectez-vous à Teams une "
                    + "seule fois quand on vous le demandera.");
        }
    }

    /** Le port de débogage utilisé. */
    public int port() {
        return port;
    }

    /** Le dossier de profil managé. */
    public Path profileDir() {
        return profileDir;
    }

    /** Le système d'exploitation courant, pour éviter à l'appelant de le redemander. */
    public static OperatingSystem currentSystem() {
        return OperatingSystem.current();
    }
}
