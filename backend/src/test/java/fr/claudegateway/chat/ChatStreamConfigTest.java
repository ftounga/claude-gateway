package fr.claudegateway.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
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
        return new ChatStreamConfig().chatStreamExecutor(8, 32, 0);
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
                (ThreadPoolTaskExecutor) new ChatStreamConfig().chatStreamExecutor(8, 2, 0);
        assertThat(pool.getMaxPoolSize()).isGreaterThanOrEqualTo(pool.getCorePoolSize());
    }
}
