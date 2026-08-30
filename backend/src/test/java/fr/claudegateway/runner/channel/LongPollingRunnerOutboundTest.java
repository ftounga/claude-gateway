package fr.claudegateway.runner.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests du canal de repli long-polling (F-38 / SF-38-09).
 *
 * <p>Ce qui est vérifié n'est pas « une trame passe » mais les quatre façons dont ce transport peut
 * mentir silencieusement : un poll qui ne rend jamais la main, une fermeture qui laisse un poll
 * bloqué jusqu'au bout de son délai, une émission acceptée sur un canal mort, et un nettoyage
 * exécuté deux fois.</p>
 */
class LongPollingRunnerOutboundTest {

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID tokenId = UUID.randomUUID();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private LongPollingRunnerOutbound channel() {
        return new LongPollingRunnerOutbound(workspaceId, userId, tokenId, null);
    }

    @Test
    void queuedFrameIsReturnedVerbatimByDrain() throws Exception {
        LongPollingRunnerOutbound channel = channel();
        String frame = "{\"type\":\"tool_call\",\"id\":\"toolu_1\",\"tool\":\"read_file\"}";

        channel.send(frame);

        assertThat(channel.drain(Duration.ofMillis(50))).containsExactly(frame);
    }

    @Test
    void drainReturnsEmptyWhenNothingArrivesBeforeTheDeadline() throws Exception {
        // Un long-poll qui expire à vide est le fonctionnement normal, pas une erreur.
        assertThat(channel().drain(Duration.ofMillis(50))).isEmpty();
    }

    @Test
    void closeWakesAWaitingDrainAndRunsCleanupOnce() throws Exception {
        AtomicInteger cleanups = new AtomicInteger();
        LongPollingRunnerOutbound channel =
                new LongPollingRunnerOutbound(workspaceId, userId, tokenId, c -> cleanups.incrementAndGet());
        CountDownLatch polling = new CountDownLatch(1);
        Future<List<String>> poll = executor.submit(() -> {
            polling.countDown();
            return channel.drain(Duration.ofSeconds(30));
        });
        assertThat(polling.await(2, TimeUnit.SECONDS)).isTrue();

        channel.close();
        channel.close(); // idempotent : le nettoyage ne doit pas être rejoué

        // Sans la sentinelle de fermeture, ce get() attendrait les 30 s du poll.
        assertThat(poll.get(2, TimeUnit.SECONDS)).isEmpty();
        assertThat(cleanups.get()).isEqualTo(1);
        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    void sendOnAClosedChannelFails() {
        LongPollingRunnerOutbound channel = channel();
        channel.close();

        // L'appel devient runner_unavailable côté dispatcher : comme une socket morte, sans rejeu.
        assertThatThrownBy(() -> channel.send("{\"type\":\"tool_call\"}")).isInstanceOf(IOException.class);
    }

    @Test
    void sendFailsOnceTheQueueIsFull() throws Exception {
        LongPollingRunnerOutbound channel = channel();
        for (int i = 0; i < LongPollingRunnerOutbound.MAX_QUEUED_FRAMES; i++) {
            channel.send("{\"seq\":" + i + "}");
        }

        // Un runner qui ne vient plus chercher ses trames ne doit pas faire enfler la mémoire du pod.
        assertThatThrownBy(() -> channel.send("{\"seq\":999}")).isInstanceOf(IOException.class);
    }

    @Test
    void drainNeverReturnsMoreThanOnePollWorthOfFrames() throws Exception {
        LongPollingRunnerOutbound channel = channel();
        for (int i = 0; i < LongPollingRunnerOutbound.MAX_FRAMES_PER_POLL + 10; i++) {
            channel.send("{\"seq\":" + i + "}");
        }

        assertThat(channel.drain(Duration.ZERO))
                .hasSize(LongPollingRunnerOutbound.MAX_FRAMES_PER_POLL);
        assertThat(channel.drain(Duration.ZERO)).hasSize(10);
    }
}
