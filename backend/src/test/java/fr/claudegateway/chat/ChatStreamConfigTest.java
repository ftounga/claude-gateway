package fr.claudegateway.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Le pool des flux SSE laisse-t-il <b>vraiment</b> quatre terminaux vivre en même temps
 * (F-70 / SF-70-01) ?
 *
 * <p>La question n'est pas rhétorique : avant F-70, la configuration ({@code core = 2},
 * {@code queue = 50}) n'en laissait tourner que <b>deux</b>, et mettait les suivants en file sans
 * rien dire. L'écran affichait « en cours » devant un agent qui dormait — exactement ce que le PO
 * refuse. Le test le vérifie par la seule méthode qui ne se laisse pas raconter d'histoire : quatre
 * tâches qui <b>s'attendent mutuellement</b>. Si le pool les sérialise, la barrière ne se franchit
 * jamais et le test échoue par expiration.</p>
 */
class ChatStreamConfigTest {

    private Executor executor() {
        return new ChatStreamConfig().chatStreamExecutor(8, 32, 0, 300);
    }

    @Test
    void fourStreamsSubmittedAtOnceRunAtTheSameTime() throws Exception {
        Executor executor = executor();
        CyclicBarrier barrier = new CyclicBarrier(4);
        AtomicInteger crossed = new AtomicInteger();

        for (int i = 0; i < 4; i++) {
            executor.execute(() -> {
                try {
                    // Chacune attend les trois autres : seul un parallélisme réel franchit ceci.
                    barrier.await(5, TimeUnit.SECONDS);
                    crossed.incrementAndGet();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (BrokenBarrierException | TimeoutException ex) {
                    // Laissé tel quel : l'assertion ci-dessous dira que rien n'est passé.
                }
            });
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (crossed.get() < 4 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(crossed.get())
                .as("quatre flux doivent tourner en parallèle, pas s'attendre dans une file")
                .isEqualTo(4);
    }

    @Test
    void theQueueIsRemovedSoThatNothingWaitsSilently() {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor();
        // Une file, si elle existait, absorberait les flux en trop avant que le pool ne grandisse :
        // c'est précisément ce qui faisait dormir le troisième terminal.
        assertThat(pool.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
        assertThat(pool.getCorePoolSize()).isEqualTo(8);
        assertThat(pool.getMaxPoolSize()).isEqualTo(32);
    }

    @Test
    void aMaxSmallerThanCoreNeverProducesAnInvalidPool() {
        ThreadPoolTaskExecutor pool =
                (ThreadPoolTaskExecutor) new ChatStreamConfig().chatStreamExecutor(8, 2, 0, 300);
        assertThat(pool.getMaxPoolSize()).isGreaterThanOrEqualTo(pool.getCorePoolSize());
    }

    // ─── Drainage à l'arrêt (F-84 / SF-84-08) ───────────────────────────────────────────────
    // La boucle d'agent tourne sur CE pool, découplée de la requête HTTP : l'arrêt gracieux du
    // serveur web ne l'attend pas. Un déploiement a tué un tour en production le 2026-09-16. Ce
    // qui suit vérifie que l'arrêt du pool attend désormais la tâche en cours, et — contrôle
    // négatif obligatoire — qu'un pool sans le réglage ne l'attend PAS.

    /** Une tâche qui dort puis lève un drapeau ; interrompue, elle ne le lève jamais. */
    private static Runnable longTask(CountDownLatch started, AtomicBoolean finished) {
        return () -> {
            started.countDown();
            try {
                Thread.sleep(400);
                finished.set(true);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        };
    }

    @Test
    void shuttingDownWaitsForTheTurnInFlightToFinish() throws Exception {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);

        pool.execute(longTask(started, finished));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        pool.shutdown();

        assertThat(finished.get())
                .as("un déploiement ne doit plus abandonner le tour en cours")
                .isTrue();
    }

    @Test
    void withoutTheDrainSettingTheSameTaskIsCutOff() throws Exception {
        // Contrôle négatif : la configuration d'AVANT SF-84-08, reproduite à l'identique.
        // Sans lui, le test précédent pourrait passer pour une raison qui n'a rien à voir.
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(8);
        pool.setMaxPoolSize(32);
        pool.setQueueCapacity(0);
        pool.initialize();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);

        pool.execute(longTask(started, finished));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        pool.shutdown();

        assertThat(finished.get())
                .as("sans le réglage, l'arrêt interrompt la tâche — le défaut corrigé par SF-84-08")
                .isFalse();
    }

    @Test
    void theDrainDelayIsTheConfiguredOne() {
        ThreadPoolTaskExecutor pool =
                (ThreadPoolTaskExecutor) new ChatStreamConfig().chatStreamExecutor(8, 32, 0, 7);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);

        long before = System.nanoTime();
        pool.execute(() -> {
            started.countDown();
            try {
                // Plus long que le délai de drainage : l'arrêt doit rendre la main quand même.
                Thread.sleep(30_000);
                finished.set(true);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }

        pool.shutdown();
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - before);

        assertThat(elapsedSeconds)
                .as("le drainage est BORNÉ : un tour sans fin ne gèle pas un déploiement correctif")
                .isBetween(6L, 20L);
        assertThat(finished.get()).isFalse();
    }

    @Test
    void aTardyTaskIsRefusedRatherThanStartedOnADyingPod() {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor();
        pool.shutdown();

        assertThatThrownBy(() -> pool.execute(() -> { }))
                .as("un pod qui meurt ne démarre pas un tour de plus")
                .isInstanceOf(TaskRejectedException.class);
    }
}
