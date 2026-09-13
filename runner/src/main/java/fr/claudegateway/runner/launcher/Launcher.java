package fr.claudegateway.runner.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import fr.claudegateway.runner.RunnerBuild;

/**
 * <b>Le lanceur</b> (F-111 / SF-111-02) : le mode par défaut de {@code claude-runner.jar}.
 *
 * <p>Il reste au premier plan dans le terminal et démarre le vrai runner comme <b>processus
 * enfant</b> depuis {@code ~/.claude-runner/versions/<version>/runner.jar}, avec les mêmes arguments,
 * le même environnement et la même console. Quand le runner sort, son <b>code de sortie</b> dit quoi
 * faire ({@link LauncherPolicy}) : s'arrêter, démarrer la version mise à jour, ou relancer après un
 * plantage.</p>
 *
 * <p><b>Sans code réseau</b> : tout ce qui parle à la gateway vit dans l'enfant. Le lanceur change
 * donc rarement — et un lanceur qui change est remplacé au prochain démarrage manuel, jamais pendant
 * qu'il tourne (fichier verrouillé sous Windows).</p>
 */
public final class Launcher {

    /** Démarre le runner directement, sans lanceur (diagnostic). */
    public static final String NO_LAUNCHER_FLAG = "--no-launcher";

    /** Délai avant une relance après plantage : un plantage immédiat ne doit pas saturer la console. */
    static final Duration RESTART_DELAY = Duration.ofSeconds(2);

    /** Attente d'un arrêt propre de l'enfant avant de le forcer (au-delà de la grâce du runner). */
    static final Duration CHILD_GRACE = Duration.ofSeconds(8);

    private final LauncherHome home;
    private final JavaChild child;
    private final RunnerBuild embedded;
    private final Path ownJar;
    private final Consumer<String> say;
    private final Clock clock;
    private final boolean windows;
    private final LauncherPolicy policy = new LauncherPolicy();
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicReference<Process> running = new AtomicReference<>();

    public Launcher(LauncherHome home, JavaChild child, RunnerBuild embedded, Path ownJar,
            Consumer<String> say, Clock clock, boolean windows) {
        this.home = home;
        this.child = child;
        this.embedded = embedded;
        this.ownJar = ownJar;
        this.say = say;
        this.clock = clock;
        this.windows = windows;
    }

    /**
     * Point d'entrée appelé par {@code RunnerLauncher} une fois Java vérifié : lanceur, ou runner
     * direct pour {@code --no-launcher}, {@code --check}, {@code --releve-teams}, et pour un
     * processus qui est déjà l'enfant d'un lanceur.
     */
    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        Optional<Path> jar = ownJar();
        if (runsDirectly(args, env) || jar.isEmpty()) {
            fr.claudegateway.runner.RunnerMain.main(withoutLauncherFlag(args));
            return;
        }
        LauncherHome home = LauncherHome.resolve(env, System.getProperty("user.home"));
        fr.claudegateway.runner.Console console = new fr.claudegateway.runner.Console();
        Launcher launcher = new Launcher(home,
                JavaChild.forThisLauncher(Map.of(JavaChild.LAUNCHER_PID_ENV,
                        String.valueOf(ProcessHandle.current().pid()), LauncherHome.HOME_ENV,
                        home.root().toString())),
                RunnerBuild.current(), jar.get(), line -> console.info("Lanceur   : " + line),
                Clock.systemUTC(),
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                        .startsWith("windows"));
        Runtime.getRuntime().addShutdownHook(new Thread(launcher::stopFromSignal, "launcher-stop"));
        System.exit(launcher.run(List.of(args)));
    }

    /**
     * Vrai si le runner doit tourner <b>sans</b> lanceur : diagnostic demandé, contrôle de vol, relevé
     * Teams (modes ponctuels qui rendent la main), ou processus déjà enfant d'un lanceur.
     */
    public static boolean runsDirectly(String[] args, Map<String, String> env) {
        if (env != null && env.get(JavaChild.LAUNCHER_PID_ENV) != null) {
            return true;
        }
        for (String arg : args == null ? new String[0] : args) {
            if (arg == null) {
                continue;
            }
            String flag = arg.contains("=") ? arg.substring(0, arg.indexOf('=')) : arg;
            if (flag.equals(NO_LAUNCHER_FLAG) || flag.equals("--check")
                    || flag.equals("--" + fr.claudegateway.runner.teams.TeamsSurveyCommand.FLAG)) {
                return true;
            }
        }
        String check = env == null ? null : env.get("CLAUDE_RUNNER_CHECK");
        return check != null && check.trim().equalsIgnoreCase("true");
    }

    /** Les arguments sans {@code --no-launcher}, que le runner n'a pas à connaître. */
    static String[] withoutLauncherFlag(String[] args) {
        return Arrays.stream(args == null ? new String[0] : args)
                .filter(arg -> !NO_LAUNCHER_FLAG.equals(arg))
                .toArray(String[]::new);
    }

    /**
     * La boucle du lanceur. Rend le code de sortie du dernier runner : celui d'un arrêt demandé, d'une
     * erreur qu'une relance ne réparerait pas, ou du plantage de trop.
     */
    public int run(List<String> args) {
        String version = startVersion();
        home.clearNextVersion();
        while (true) {
            Path jar = jarFor(version);
            Process process;
            try {
                process = child.start(jar, args);
            } catch (IOException e) {
                say.accept("impossible de démarrer le runner " + version + " (" + e.getMessage() + ").");
                return 1;
            }
            running.set(process);
            if (stopping.get()) {
                // Arrêt demandé pendant le démarrage : ne pas laisser un enfant que personne n'attend.
                stopChild(process);
            }
            int code = waitFor(process);
            running.set(null);
            if (stopping.get()) {
                return code;
            }
            switch (policy.onExit(code, clock.instant())) {
                case STOP -> {
                    return code;
                }
                case GIVE_UP -> {
                    say.accept("le runner s'est arrêté " + policy.recentCrashes() + " fois en "
                            + LauncherPolicy.CRASH_WINDOW.toMinutes() + " minutes (dernier code " + code
                            + ") : le lanceur s'arrête. Relancez-le quand la cause est réglée.");
                    return code;
                }
                case RESTART -> {
                    say.accept("le runner s'est arrêté de façon inattendue (code " + code
                            + ") — relance " + policy.recentCrashes() + "/" + LauncherPolicy.MAX_RESTARTS
                            + "…");
                    if (!pause(RESTART_DELAY)) {
                        return code;
                    }
                }
                case UPDATE -> version = nextVersionAfter(version);
            }
        }
    }

    /** Ce que le crochet d'arrêt exécute ({@code Ctrl+C}, fermeture du terminal) : l'enfant suit. */
    public void stopFromSignal() {
        stopping.set(true);
        Process process = running.get();
        if (process != null) {
            stopChild(process);
        }
    }

    /**
     * La version à démarrer : celle du jar lancé, installée au premier lancement, sauf si une version
     * <b>plus récente</b> a déjà été installée par une mise à jour. Jamais une plus ancienne.
     */
    String startVersion() {
        String embeddedId = embedded.id();
        if (!home.isInstalled(embeddedId)) {
            try {
                home.install(embeddedId, ownJar);
                say.accept("runner " + embeddedId + " installé dans " + home.jarOf(embeddedId).getParent()
                        + ".");
            } catch (IOException | RuntimeException e) {
                say.accept("installation dans " + home.root() + " impossible (" + e.getMessage()
                        + ") : le runner démarre depuis " + ownJar + ", sans mise à jour automatique.");
            }
        }
        Optional<String> current = home.currentVersion().filter(home::isInstalled);
        if (current.isPresent() && isNewer(current.get(), embeddedId)) {
            return current.get();
        }
        try {
            if (home.isInstalled(embeddedId)) {
                home.setCurrentVersion(embeddedId);
            }
        } catch (IOException e) {
            // Le démarrage n'en dépend pas : current-version n'est qu'un souvenir.
        }
        return embeddedId;
    }

    /** Après une sortie en 75 : la version annoncée si elle est installée, intacte et plus récente. */
    private String nextVersionAfter(String version) {
        Optional<String> next = home.nextVersion();
        home.clearNextVersion();
        if (next.isEmpty() || !home.isInstalled(next.get())) {
            say.accept("mise à jour annoncée, mais aucune version installée n'a été trouvée : le runner "
                    + version + " redémarre.");
            return version;
        }
        if (!isNewer(next.get(), version)) {
            say.accept("la version " + next.get() + " n'est pas plus récente que " + version
                    + " : elle n'est pas démarrée.");
            return version;
        }
        try {
            home.setCurrentVersion(next.get());
        } catch (IOException e) {
            say.accept("current-version non écrit (" + e.getMessage() + ") : le prochain démarrage "
                    + "manuel repartira de la version du jar lancé.");
        }
        policy.resetCrashes();
        say.accept("mise à jour : démarrage du runner " + next.get() + " (depuis " + version + ").");
        return next.get();
    }

    private Path jarFor(String version) {
        return home.isInstalled(version) ? home.jarOf(version) : ownJar;
    }

    private int waitFor(Process process) {
        while (true) {
            try {
                return process.waitFor();
            } catch (InterruptedException e) {
                // Le lanceur ne rend jamais la main en laissant un enfant derrière lui.
                stopChild(process);
            }
        }
    }

    /**
     * Arrête l'enfant sans laisser d'orphelin. Sous Linux et macOS, {@code destroy()} envoie SIGTERM :
     * le runner exécute son propre crochet d'arrêt. Sous Windows, {@code destroy()} tue sans crochet ;
     * mais {@code Ctrl+C} y atteint déjà tous les processus de la console — on attend donc d'abord.
     * Passé le délai, l'enfant est tué dans tous les cas.
     */
    void stopChild(Process process) {
        if (!process.isAlive()) {
            return;
        }
        if (!windows) {
            process.destroy();
        }
        try {
            if (!process.waitFor(CHILD_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private boolean pause(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
            return !stopping.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static boolean isNewer(String candidate, String reference) {
        Optional<RunnerBuild> a = RunnerBuild.parseId(candidate);
        Optional<RunnerBuild> b = RunnerBuild.parseId(reference);
        return a.isPresent() && b.isPresent() && a.get().compareTo(b.get()) > 0;
    }

    /** Le jar dont ce code est chargé, ou vide s'il ne vient pas d'un jar (IDE, classes de test). */
    static Optional<Path> ownJar() {
        try {
            Path location = Path.of(Launcher.class.getProtectionDomain().getCodeSource().getLocation()
                    .toURI());
            return Files.isRegularFile(location) && location.toString().endsWith(".jar")
                    ? Optional.of(location) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Arguments en liste modifiable (tests). */
    static List<String> argsOf(String... args) {
        return new ArrayList<>(Arrays.asList(args));
    }
}
