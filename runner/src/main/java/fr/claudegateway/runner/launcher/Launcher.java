package fr.claudegateway.runner.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * <p><b>Santé et retour arrière</b> (SF-111-05) : une version fraîchement mise à jour est <b>à l'essai</b>.
 * Elle doit se reconnecter en {@link #HEALTH_TIMEOUT} — le runner écrit un témoin dès que sa liaison est
 * établie ; sinon, ou après {@link LauncherPolicy#TRIAL_CRASHES} plantages, le lanceur revient à la version
 * précédente et laisse un rapport que le runner revenu remet à la gateway.</p>
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

    /** Délai laissé à une version mise à jour pour se reconnecter (cadrage §3.8). */
    public static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(90);

    /** Versions précédentes conservées dans {@code versions/} (cadrage §4). */
    static final int KEEP_PREVIOUS = 2;

    private final LauncherHome home;
    private final JavaChild child;
    private final RunnerBuild embedded;
    private final Path ownJar;
    private final Consumer<String> say;
    private final Clock clock;
    private final boolean windows;
    private final Duration healthTimeout;
    private final LauncherPolicy policy = new LauncherPolicy();
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicReference<Process> running = new AtomicReference<>();

    public Launcher(LauncherHome home, JavaChild child, RunnerBuild embedded, Path ownJar,
            Consumer<String> say, Clock clock, boolean windows) {
        this(home, child, embedded, ownJar, say, clock, windows, HEALTH_TIMEOUT);
    }

    Launcher(LauncherHome home, JavaChild child, RunnerBuild embedded, Path ownJar, Consumer<String> say,
            Clock clock, boolean windows, Duration healthTimeout) {
        this.home = home;
        this.child = child;
        this.embedded = embedded;
        this.ownJar = ownJar;
        this.say = say;
        this.clock = clock;
        this.windows = windows;
        this.healthTimeout = healthTimeout;
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

    /** Une version mise à jour, à l'essai jusqu'à sa première connexion (SF-111-05). */
    static final class Trial {
        final String from;
        final String to;
        Instant deadline;
        int crashes;

        Trial(String from, String to, Instant deadline) {
            this.from = from;
            this.to = to;
            this.deadline = deadline;
        }
    }

    /**
     * La boucle du lanceur. Rend le code de sortie du dernier runner : celui d'un arrêt demandé, d'une
     * erreur qu'une relance ne réparerait pas, ou du plantage de trop.
     */
    public int run(List<String> args) {
        String version = startVersion();
        home.clearNextVersion();
        Trial trial = null;
        while (true) {
            Path jar = jarFor(version);
            if (trial != null) {
                home.clearHealth();
                trial.deadline = clock.instant().plus(healthTimeout);
            }
            Process process;
            try {
                process = child.start(jar, args);
            } catch (IOException e) {
                say.accept("impossible de démarrer le runner " + version + " (" + e.getMessage() + ").");
                if (trial != null) {
                    version = rollBack(trial, "la version " + trial.to + " n'a pas pu démarrer");
                    trial = null;
                    continue;
                }
                return 1;
            }
            running.set(process);
            if (stopping.get()) {
                // Arrêt demandé pendant le démarrage : ne pas laisser un enfant que personne n'attend.
                stopChild(process);
            }
            Integer code = watch(process, trial);
            running.set(null);
            if (stopping.get()) {
                return code == null ? 0 : code;
            }
            if (trial != null && (trial.deadline == Instant.MAX
                    || (code != null && trial.to.equals(home.connectedVersion().orElse(null))))) {
                // Confirmé pendant l'attente (ou juste avant de sortir) : la suite est une vie ordinaire.
                confirm(trial);
                trial = null;
            }
            if (trial != null) {
                if (code == null) {
                    version = rollBack(trial, "la version " + trial.to + " ne s'est pas reconnectée en "
                            + healthTimeout.toSeconds() + " s");
                    trial = null;
                    continue;
                }
                if (code == 0) {
                    return 0;
                }
                if (code == LauncherPolicy.EXIT_UPDATE) {
                    trial = null; // une nouvelle mise à jour ouvre son propre essai ci-dessous
                } else {
                    trial.crashes++;
                    if (trial.crashes >= LauncherPolicy.TRIAL_CRASHES) {
                        version = rollBack(trial, trial.crashes + " plantages de la version " + trial.to
                                + " (dernier code " + code + ")");
                        trial = null;
                        continue;
                    }
                    say.accept("la version " + trial.to + " s'est arrêtée (code " + code + ") — nouvel essai "
                            + trial.crashes + "/" + (LauncherPolicy.TRIAL_CRASHES - 1) + "…");
                    if (!pause(RESTART_DELAY)) {
                        return code;
                    }
                    continue;
                }
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
                case UPDATE -> {
                    String next = nextVersionAfter(version);
                    if (!next.equals(version)) {
                        trial = new Trial(version, next, clock.instant().plus(healthTimeout));
                        version = next;
                    } else if (policy.onExit(1, clock.instant()) == LauncherPolicy.Action.GIVE_UP) {
                        // Un 75 sans version à démarrer, répété, est une panne : il compte comme un
                        // plantage, sinon le lanceur relancerait en boucle serrée.
                        say.accept("le runner annonce des mises à jour introuvables à répétition : le lanceur "
                                + "s'arrête.");
                        return LauncherPolicy.EXIT_UPDATE;
                    } else if (!pause(RESTART_DELAY)) {
                        return LauncherPolicy.EXIT_UPDATE;
                    }
                }
            }
        }
    }

    /**
     * Attend la sortie de l'enfant ; pendant un essai, surveille le témoin de santé et l'échéance.
     *
     * @return le code de sortie, ou {@code null} si l'essai a expiré (l'enfant a alors été arrêté)
     */
    private Integer watch(Process process, Trial trial) {
        while (true) {
            try {
                if (process.waitFor(1, TimeUnit.SECONDS)) {
                    return process.exitValue();
                }
            } catch (InterruptedException e) {
                // Le lanceur ne rend jamais la main en laissant un enfant derrière lui.
                stopChild(process);
                Thread.currentThread().interrupt();
                return process.isAlive() ? 1 : process.exitValue();
            }
            if (trial == null || stopping.get()) {
                continue;
            }
            if (trial.to.equals(home.connectedVersion().orElse(null))) {
                confirm(trial);
                trial.deadline = Instant.MAX; // confirmé : la suite est une vie ordinaire
                trial.crashes = 0;
                continue;
            }
            if (clock.instant().isAfter(trial.deadline)) {
                stopChild(process);
                return null;
            }
        }
    }

    /** L'essai a réussi : la version devient la courante, les plus anciennes sont élaguées. */
    private void confirm(Trial trial) {
        if (trial.deadline == Instant.MAX) {
            return;
        }
        try {
            home.setCurrentVersion(trial.to);
        } catch (IOException e) {
            say.accept("current-version non écrit (" + e.getMessage() + ").");
        }
        List<String> removed = home.prune(trial.to, KEEP_PREVIOUS);
        policy.resetCrashes();
        say.accept("runner " + trial.to + " connecté : mise à jour confirmée"
                + (removed.isEmpty() ? "." : " (anciennes versions supprimées : " + String.join(", ", removed) + ")."));
    }

    /**
     * Retour à la version précédente : le <b>seul</b> chemin qui démarre une version plus ancienne que
     * celle en cours. Le rapport est laissé au runner revenu, qui le remet à la gateway.
     */
    private String rollBack(Trial trial, String reason) {
        say.accept("mise à jour vers " + trial.to + " échouée : " + reason + ". Retour à " + trial.from + ".");
        try {
            home.writeReport(new LauncherHome.UpdateReport(trial.from, trial.to, "rolled_back", reason));
        } catch (IOException e) {
            say.accept("rapport de retour non écrit (" + e.getMessage() + ").");
        }
        home.deleteVersion(trial.to);
        home.clearHealth();
        policy.resetCrashes();
        String back = home.isInstalled(trial.from) ? trial.from : embedded.id();
        try {
            if (home.isInstalled(back)) {
                home.setCurrentVersion(back);
            }
        } catch (IOException e) {
            // Le démarrage n'en dépend pas.
        }
        return back;
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

    /**
     * Après une sortie en 75 : la version annoncée si elle est installée, intacte et plus récente. Elle
     * n'est <b>pas</b> encore la courante — elle le devient à sa première connexion (SF-111-05).
     */
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
        policy.resetCrashes();
        say.accept("mise à jour : démarrage du runner " + next.get() + " (depuis " + version + "), à l'essai "
                + healthTimeout.toSeconds() + " s.");
        return next.get();
    }

    private Path jarFor(String version) {
        return home.isInstalled(version) ? home.jarOf(version) : ownJar;
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
