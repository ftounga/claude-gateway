package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * File d'émission mono-thread (F-38 / SF-38-04). Le point vérifié est celui qui casse la socket en
 * production : {@code java.net.http.WebSocket} interdit un {@code sendText} tant que le précédent
 * n'est pas terminé — la file doit donc n'avoir <b>jamais</b> deux envois en vol.
 */
class FrameSenderTest {

    @Test
    void serialiseLesEnvoisEtPreserveLOrdre() throws InterruptedException {
        List<String> completed = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(3);

        try (FrameSender sender = new FrameSender(new Console())) {
            sender.attach(frame -> {
                int current = inFlight.incrementAndGet();
                maxInFlight.updateAndGet(max -> Math.max(max, current));
                return CompletableFuture.runAsync(() -> sleep(30)).whenComplete((ignored, error) -> {
                    completed.add(frame);
                    inFlight.decrementAndGet();
                    done.countDown();
                });
            });

            sender.send("un");
            sender.send("deux");
            sender.send("trois");

            assertTrue(done.await(5, TimeUnit.SECONDS), "Les trames doivent être émises");
        }

        assertEquals(1, maxInFlight.get(), "Aucun envoi ne doit démarrer avant la fin du précédent");
        assertEquals(List.of("un", "deux", "trois"), completed);
    }

    @Test
    void ignoreUnEnvoiSansTransportAttache() {
        try (FrameSender sender = new FrameSender(new Console())) {
            assertDoesNotThrow(() -> sender.send("{\"type\":\"heartbeat\"}"));
        }
    }

    @Test
    void abandonneLesTramesApresDetachement() throws InterruptedException {
        LinkedBlockingQueue<String> sent = new LinkedBlockingQueue<>();
        try (FrameSender sender = new FrameSender(new Console())) {
            sender.attach(frame -> {
                sent.add(frame);
                return CompletableFuture.completedFuture(null);
            });
            sender.send("avant");
            assertEquals("avant", sent.poll(5, TimeUnit.SECONDS));

            sender.detach();
            sender.send("apres");

            assertNull(sent.poll(300, TimeUnit.MILLISECONDS), "Rien ne doit partir après détachement");
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
