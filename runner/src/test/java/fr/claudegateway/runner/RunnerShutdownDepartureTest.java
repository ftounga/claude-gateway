package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * F-247 / SF-247-09 — le relevé de départ, sur le <b>vrai</b> crochet d'arrêt ({@link RunnerShutdown#task}) : il passe
 * avant que les transports soient coupés, il est borné, et un échec n'empêche jamais l'arrêt.
 */
class RunnerShutdownDepartureTest {

    private static final Console QUIET = new Console(line -> { },
            ConsoleEncoding.forCharset(java.nio.charset.StandardCharsets.UTF_8));

    @Test
    void departureRunsBeforeTransportsAreCut() {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch stopped = new CountDownLatch(1);
        stopped.countDown();
        RunnerShutdown.task(new AtomicBoolean(), () -> order.add("départ"), Duration.ofSeconds(2),
                () -> order.add("coupure"), stopped, Duration.ofMillis(100), QUIET).run();
        assertEquals(List.of("départ", "coupure"), order);
    }

    @Test
    void aSlowGatewayNeverHoldsTheStopBeyondTheCap() {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch stopped = new CountDownLatch(1);
        stopped.countDown();
        long t0 = System.nanoTime();
        RunnerShutdown.task(new AtomicBoolean(), () -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, Duration.ofMillis(300), () -> order.add("coupure"), stopped, Duration.ofMillis(100), QUIET).run();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 2_000, "Arrêt en " + ms + " ms");
        assertEquals(List.of("coupure"), order);
        assertEquals(8, RunnerShutdown.DEPARTURE.toSeconds());
    }

    @Test
    void aFailingDepartureDoesNotPreventTheStop() {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch stopped = new CountDownLatch(1);
        stopped.countDown();
        RunnerShutdown.task(new AtomicBoolean(), () -> {
            throw new IllegalStateException("gateway muette");
        }, Duration.ofSeconds(1), () -> order.add("coupure"), stopped, Duration.ofMillis(100), QUIET).run();
        assertEquals(List.of("coupure"), order);
    }

    @Test
    void anOldGatewayOrNoTokenIsANoOp() {
        RunnerDeparture.request(null, "http://x/runner/departure", "t", Duration.ofSeconds(1)).run();
        RunnerDeparture.request(java.net.http.HttpClient.newHttpClient(), "http://127.0.0.1:9/runner/departure", "",
                Duration.ofSeconds(1)).run();
    }
}
