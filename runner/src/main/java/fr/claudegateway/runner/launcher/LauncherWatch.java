package fr.claudegateway.runner.launcher;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;

import fr.claudegateway.runner.Console;

/**
 * Le runner <b>surveille son lanceur</b> (F-111 / SF-111-02).
 *
 * <p>Le crochet d'arrêt du lanceur arrête l'enfant sur {@code Ctrl+C} ou une fermeture ordinaire. Il
 * ne s'exécute pas quand le lanceur est tué sans ménagement ({@code kill -9}, gestionnaire des tâches) :
 * le runner resterait alors seul, connecté, invisible dans un terminal qui n'existe plus. Il vérifie
 * donc, toutes les deux secondes, que le processus qui l'a lancé vit encore — et s'arrête proprement
 * sinon (son propre crochet ferme la liaison).</p>
 */
public final class LauncherWatch {

    static final Duration PERIOD = Duration.ofSeconds(2);

    private LauncherWatch() {
    }

    /** Vrai si ce processus a été démarré par un lanceur. */
    public static boolean underLauncher(Map<String, String> env) {
        return launcherPid(env).isPresent();
    }

    private static final java.util.concurrent.atomic.AtomicBoolean CONNECTED_REPORTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Dit au lanceur que la liaison tient (F-111 / SF-111-05) : écrit le témoin de santé, une fois par
     * processus. C'est ce qui confirme une version à l'essai. Ne lève jamais.
     */
    public static void reportConnected(Map<String, String> env) {
        if (!underLauncher(env) || !CONNECTED_REPORTED.compareAndSet(false, true)) {
            return;
        }
        try {
            LauncherHome.resolve(env, System.getProperty("user.home"))
                    .markConnected(fr.claudegateway.runner.RunnerBuild.current().id(), ProcessHandle.current().pid());
        } catch (java.io.IOException | RuntimeException e) {
            CONNECTED_REPORTED.set(false); // réessayé à la prochaine connexion
        }
    }

    /**
     * Le rapport d'un retour arrière laissé par le lanceur (F-111 / SF-111-05), à remettre dans la trame
     * {@code ready} ; {@code null} s'il n'y en a pas ou s'il est illisible (il est alors effacé).
     */
    public static com.fasterxml.jackson.databind.JsonNode pendingReport(Map<String, String> env) {
        if (!underLauncher(env)) {
            return null;
        }
        LauncherHome home = LauncherHome.resolve(env, System.getProperty("user.home"));
        java.nio.file.Path file = home.reportFile();
        if (!java.nio.file.Files.isRegularFile(file)) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode report =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(file.toFile());
            if (report != null && report.isObject() && report.path("to").isTextual()
                    && report.path("result").isTextual()) {
                return report;
            }
        } catch (java.io.IOException | RuntimeException e) {
            // illisible : effacé ci-dessous
        }
        home.clearReport();
        return null;
    }

    /** Efface le rapport une fois remis. */
    public static void clearReport(Map<String, String> env) {
        LauncherHome.resolve(env, System.getProperty("user.home")).clearReport();
    }

    /** Démarre la surveillance si ce processus a un lanceur. Ne fait rien sinon. */
    public static void startIfUnderLauncher(Map<String, String> env, Console console) {
        Optional<Long> pid = launcherPid(env);
        if (pid.isEmpty()) {
            return;
        }
        Thread watch = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(PERIOD.toMillis());
                } catch (InterruptedException e) {
                    return;
                }
                if (launcherGone(pid.get(), LauncherWatch::aliveness)) {
                    console.warn("Le lanceur s'est arrêté sans prévenir : le runner s'arrête aussi.");
                    System.exit(0);
                    return;
                }
            }
        }, "runner-launcher-watch");
        watch.setDaemon(true);
        watch.start();
    }

    /**
     * Vrai si le lanceur a disparu. Un système qui ne sait pas répondre ({@code Optional.empty()} pour
     * un processus introuvable) vaut « disparu » : un identifiant qui ne désigne plus rien ne protège
     * plus rien.
     */
    static boolean launcherGone(long pid, LongFunction<Optional<Boolean>> alive) {
        return !alive.apply(pid).orElse(false);
    }

    static Optional<Long> launcherPid(Map<String, String> env) {
        String raw = env == null ? null : env.get(JavaChild.LAUNCHER_PID_ENV);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            long pid = Long.parseLong(raw.trim());
            return pid > 0 ? Optional.of(pid) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Boolean> aliveness(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive);
    }
}
