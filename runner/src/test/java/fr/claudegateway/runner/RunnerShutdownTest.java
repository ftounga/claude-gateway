package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * F-82 / SF-82-01 — {@code Ctrl-C} rend <b>toujours</b> la main.
 *
 * <p><b>Le test qui prouve la subfeature</b> est le premier : une session <b>volontairement
 * bloquée</b> — le verrou n'est jamais libéré, exactement comme une lecture réseau qui ne rend rien
 * — et le crochet qui sort quand même, dans le délai borné, en le disant. Sans lui, rien ne
 * distinguerait ce correctif de l'état d'avant : un {@code await()} sans borne passe au vert dans
 * tout test où la session finit par se fermer.</p>
 *
 * <p>Le crochet exécuté ici est <b>le crochet lui-même</b> : {@link RunnerShutdown#task} rend le
 * {@code Runnable} que {@code RunnerMain} confie à la JVM. Aucune imitation ne s'intercale.</p>
 */
class RunnerShutdownTest {

    /** Marge au-dessus du délai : le temps d'ordonnancer un thread, pas plus. */
    private static final Duration MARGIN = Duration.ofSeconds(2);

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final Console console =
            new Console(lines::add, ConsoleEncoding.forCharset(StandardCharsets.UTF_8));

    @Test
    @Timeout(20)
    void une_session_bloquee_rend_la_main_dans_le_delai_borne() throws Exception {
        // La session ne rendra JAMAIS la main : ce verrou n'est compté nulle part.
        CountDownLatch stopped = new CountDownLatch(1);
        Duration grace = Duration.ofSeconds(1);
        Runnable hook = RunnerShutdown.task(new AtomicBoolean(), () -> { }, stopped, grace, console);

        long elapsedMs = runAndWait(hook, grace.plus(MARGIN));

        assertTrue(elapsedMs >= grace.toMillis(),
                "le crochet a attendu au moins le délai avant d'abandonner : " + elapsedMs + " ms");
        assertTrue(elapsedMs < grace.plus(MARGIN).toMillis(),
                "le crochet a rendu la main dans le délai borné : " + elapsedMs + " ms");
    }

    @Test
    @Timeout(20)
    void une_session_bloquee_dit_que_la_fermeture_propre_a_echoue() throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        Duration grace = Duration.ofSeconds(1);
        Runnable hook = RunnerShutdown.task(new AtomicBoolean(), () -> { }, stopped, grace, console);

        runAndWait(hook, grace.plus(MARGIN));

        String all = String.join("\n", lines);
        assertFalse(lines.isEmpty(), "l'échec de fermeture est dit, pas tu");
        assertTrue(all.contains("ne s'est pas fermée"), all);
        // Le délai est NOMMÉ : sans lui, l'utilisateur ne sait pas s'il a attendu trop peu.
        assertTrue(all.contains(grace.toSeconds() + " s"), all);
        assertTrue(all.contains("rend la main"), all);
        // La cause probable, et ce que ça coûte.
        assertTrue(all.contains("long-poll"), all);
        assertTrue(all.contains("Rien n'est perdu sur cette machine"), all);
        assertTrue(all.contains("balayage d'inactivité"), all);
    }

    @Test
    @Timeout(20)
    void une_session_qui_se_ferme_rend_la_main_sans_aucun_message_d_echec() throws Exception {
        // Session normale : le finally d'execute() a libéré le verrou.
        CountDownLatch stopped = new CountDownLatch(1);
        stopped.countDown();
        Runnable hook = RunnerShutdown.task(new AtomicBoolean(), () -> { }, stopped,
                RunnerShutdown.GRACE, console);

        long elapsedMs = runAndWait(hook, Duration.ofSeconds(3));

        assertTrue(elapsedMs < 1_000, "aucune attente sur une session déjà fermée : " + elapsedMs + " ms");
        assertEquals(List.of(), lines,
                "un arrêt qui se passe bien n'ajoute AUCUNE ligne : " + lines);
    }

    @Test
    @Timeout(20)
    void les_ordres_d_arret_partent_avant_l_attente() throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicLong stopRequestedAt = new AtomicLong();
        AtomicBoolean shuttingDown = new AtomicBoolean();
        AtomicBoolean flagWasSetBeforeStop = new AtomicBoolean();
        Runnable hook = RunnerShutdown.task(shuttingDown, () -> {
            // Le drapeau est posé AVANT l'ordre d'arrêt : c'est lui qui empêche la session
            // d'enchaîner sur un repli de transport après un Ctrl-C.
            flagWasSetBeforeStop.set(shuttingDown.get());
            stopRequestedAt.set(System.nanoTime());
            // La session coopère : elle rend la main dès qu'on le lui demande.
            stopped.countDown();
        }, stopped, RunnerShutdown.GRACE, console);

        long before = System.nanoTime();
        runAndWait(hook, Duration.ofSeconds(3));

        assertTrue(flagWasSetBeforeStop.get(), "shuttingDown est posé avant tout ordre d'arrêt");
        assertTrue(stopRequestedAt.get() >= before, "l'ordre d'arrêt est bien parti");
        assertEquals(List.of(), lines, "une session qui coopère n'ajoute aucun message : " + lines);
    }

    @Test
    void le_delai_par_defaut_est_de_cinq_secondes() {
        assertEquals(Duration.ofSeconds(5), RunnerShutdown.GRACE);
    }

    @Test
    @Timeout(20)
    void une_interruption_du_crochet_ne_produit_aucun_message_d_echec() throws Exception {
        CountDownLatch stopped = new CountDownLatch(1); // jamais libéré
        Runnable hook = RunnerShutdown.task(new AtomicBoolean(), () -> { }, stopped,
                Duration.ofMinutes(5), console);
        AtomicBoolean interruptedFlagKept = new AtomicBoolean();

        Thread thread = new Thread(() -> {
            hook.run();
            interruptedFlagKept.set(Thread.currentThread().isInterrupted());
        }, "test-shutdown-interrupt");
        thread.start();
        // Laisser le crochet entrer dans son attente, puis l'interrompre.
        Thread.sleep(200);
        thread.interrupt();
        thread.join(Duration.ofSeconds(5).toMillis());

        assertFalse(thread.isAlive(), "une interruption rend la main immédiatement");
        assertTrue(interruptedFlagKept.get(), "le drapeau d'interruption est reposé");
        assertEquals(List.of(), lines,
                "une interruption n'est pas un échec de fermeture : " + lines);
    }

    /** Exécute le crochet sur un thread dédié et rend la durée observée, en millisecondes. */
    private long runAndWait(Runnable hook, Duration limit) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        long before = System.nanoTime();
        Thread thread = new Thread(() -> {
            try {
                hook.run();
            } finally {
                done.countDown();
            }
        }, "test-shutdown");
        thread.start();
        assertTrue(done.await(limit.toMillis(), TimeUnit.MILLISECONDS),
                "le crochet a rendu la main avant " + limit.toMillis() + " ms");
        return Duration.ofNanos(System.nanoTime() - before).toMillis();
    }
}
