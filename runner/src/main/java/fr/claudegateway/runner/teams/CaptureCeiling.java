package fr.claudegateway.runner.teams;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * <b>L'arrêt de sécurité</b> (F-91 / SF-91-02) : une capture s'arrête d'elle-même au bout de trois
 * heures.
 *
 * <h2>Ce qu'il traite, que le témoin ne traite pas</h2>
 *
 * <p>Le témoin au premier plan empêche la capture oubliée <b>quand l'écran est visible</b>. Il
 * n'empêche rien quand la session est verrouillée, l'écran éteint, ou le poste simplement laissé
 * allumé le soir. Le plafond est la moitié qui manque : au bout de trois heures, l'enregistrement
 * <b>s'arrête</b>, et il le dit.</p>
 *
 * <h2>Pourquoi trois heures, et pourquoi un arrêt et non un avertissement</h2>
 *
 * <p>Trois heures est plus long que toute réunion normale, et beaucoup plus court qu'une nuit. Et
 * c'est un <b>arrêt</b>, parce qu'un avertissement que personne ne lit ne protège personne — or
 * personne ne lit un avertissement sur un écran verrouillé.</p>
 *
 * <p><b>Le plafond est dit au démarrage</b> ({@link #sentence()}), jamais découvert à l'arrêt : une
 * limite qu'on apprend en la heurtant est une panne.</p>
 */
public final class CaptureCeiling {

    /** Le plafond. Constante nommée : elle se règle sans se chercher. */
    public static final Duration MAX = Duration.ofHours(3);

    private final Duration max;
    private final Timer timer;
    private AutoCloseable armed;

    public CaptureCeiling() {
        this(MAX, realTimer());
    }

    CaptureCeiling(Duration max, Timer timer) {
        this.max = max == null ? MAX : max;
        this.timer = timer;
    }

    /** Ce qu'on dit au démarrage. Jamais après. */
    public String sentence() {
        return "Cette capture s'arrêtera d'elle-même au bout de " + CaptureRecord.clock(max)
                + " si personne ne l'arrête : un enregistrement oublié capterait la réunion "
                + "suivante.";
    }

    public Duration max() {
        return max;
    }

    /** Arme l'arrêt de sécurité pour cette capture. Un armement en remplace un autre. */
    public synchronized void arm(Runnable stop) {
        disarm();
        armed = timer.schedule(max, stop);
    }

    /** Désarme — la capture s'est arrêtée avant le plafond, ce qui est le cas normal. */
    public synchronized void disarm() {
        AutoCloseable current = armed;
        armed = null;
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (Exception e) {
            // Un minuteur qu'on ne peut pas annuler ne doit pas empêcher d'arrêter une capture :
            // l'arrêt lui-même est idempotent.
        }
    }

    /** Le minuteur, isolé pour que le plafond s'éprouve sans attendre trois heures. */
    @FunctionalInterface
    interface Timer {
        AutoCloseable schedule(Duration delay, Runnable task);
    }

    /** Le minuteur réel : un fil unique, démon — il ne retient pas l'arrêt du runner. */
    static Timer realTimer() {
        ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "teams-capture-ceiling");
            thread.setDaemon(true);
            return thread;
        });
        return (delay, task) -> {
            ScheduledFuture<?> future =
                    pool.schedule(task, Math.max(1L, delay.toMillis()), TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        };
    }
}
