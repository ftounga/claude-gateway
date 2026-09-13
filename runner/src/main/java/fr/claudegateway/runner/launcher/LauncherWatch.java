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
