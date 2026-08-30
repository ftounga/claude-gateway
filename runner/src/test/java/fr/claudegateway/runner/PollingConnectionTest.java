package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests de la boucle de repli long-polling du runner (F-38 / SF-38-09).
 *
 * <p>Le repli n'a d'intérêt que s'il se comporte <b>exactement</b> comme le WebSocket : même trame
 * {@code ready} à l'ouverture, une et une seule trame terminale par appel, un type inconnu ignoré, et
 * un arrêt qui prévient la gateway. C'est cela qui est vérifié ici, sans réseau.</p>
 */
class PollingConnectionTest {

    @TempDir
    Path workspace;

    /** Transport factice : le réseau est remplacé par deux files, la boucle reste la vraie. */
    private static final class FakeTransport implements PollingTransport {
        private final BlockingQueue<List<String>> inbound = new LinkedBlockingQueue<>();
        private final List<String> sent = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean disconnected = new AtomicBoolean(false);
        private volatile RuntimeException failWith;

        @Override
        public List<String> poll(long waitMs) {
            if (failWith != null) {
                throw failWith;
            }
            try {
                List<String> batch = inbound.poll(100, TimeUnit.MILLISECONDS);
                return batch == null ? List.of() : batch;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        }

        @Override
        public void send(List<String> frames) {
            sent.addAll(frames);
        }

        @Override
        public void disconnect() {
            disconnected.set(true);
        }

        List<String> sentSnapshot() {
            synchronized (sent) {
                return List.copyOf(sent);
            }
        }
    }

    private RunnerConfig config() {
        return RunnerConfig.resolve(new String[] {
                "--gateway", "https://portal.example.com/api",
                "--workspace", workspace.toString(),
                "--code", "AB2C3D4E",
                "--transport", "polling"
        }, Map.of());
    }

    private static boolean awaitUntil(Supplier<Boolean> condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.get()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static boolean anySent(FakeTransport transport, String needle) {
        return transport.sentSnapshot().stream().anyMatch(frame -> frame.contains(needle));
    }

    @Test
    void announcesItselfWithAReadyFrameThenServesToolCalls() throws Exception {
        Files.writeString(workspace.resolve("note.txt"), "bonjour");
        FakeTransport transport = new FakeTransport();
        PollingConnection connection = new PollingConnection(transport, config(), new Console());
        Thread loop = new Thread(connection::run, "polling-test");
        loop.start();
        try {
            assertTrue(awaitUntil(() -> anySent(transport, "\"type\":\"ready\"")),
                    "le runner doit s'annoncer comme sur la socket");

            transport.inbound.put(List.of("{\"type\":\"tool_call\",\"id\":\"toolu_1\","
                    + "\"tool\":\"read_file\",\"input\":{\"path\":\"note.txt\"},\"timeoutMs\":5000}"));

            assertTrue(awaitUntil(() -> anySent(transport, "\"type\":\"tool_result\"")),
                    "un tool_call doit produire une trame terminale");
            List<String> results = transport.sentSnapshot().stream()
                    .filter(frame -> frame.contains("\"type\":\"tool_result\""))
                    .toList();
            // Exactement UNE trame terminale par id (contrat §1).
            assertEquals(1, results.size());
            assertTrue(results.get(0).contains("\"id\":\"toolu_1\""));
            assertTrue(results.get(0).contains("\"ok\":true"));
            assertTrue(results.get(0).contains("bonjour"));
        } finally {
            connection.stop();
            loop.join(5_000);
        }
    }

    @Test
    void anUnknownFrameTypeIsIgnoredWithoutAnswering() throws Exception {
        FakeTransport transport = new FakeTransport();
        PollingConnection connection = new PollingConnection(transport, config(), new Console());
        Thread loop = new Thread(connection::run, "polling-test");
        loop.start();
        try {
            assertTrue(awaitUntil(() -> anySent(transport, "\"type\":\"ready\"")));

            transport.inbound.put(List.of("{\"type\":\"venu_du_futur\",\"id\":\"x\"}"));
            Thread.sleep(300);

            // Compatibilité ascendante (contrat §0) : ni protocol_error, ni tool_result, ni coupure.
            assertFalse(anySent(transport, "protocol_error"));
            assertFalse(anySent(transport, "tool_result"));
            assertTrue(loop.isAlive());
        } finally {
            connection.stop();
            loop.join(5_000);
        }
    }

    @Test
    void stoppingTellsTheGatewayTheRunnerIsLeaving() throws Exception {
        FakeTransport transport = new FakeTransport();
        PollingConnection connection = new PollingConnection(transport, config(), new Console());
        Thread loop = new Thread(connection::run, "polling-test");
        loop.start();
        assertTrue(awaitUntil(() -> anySent(transport, "\"type\":\"ready\"")));

        connection.stop();
        loop.join(5_000);

        assertFalse(loop.isAlive());
        assertTrue(transport.disconnected.get(), "un Ctrl-C doit libérer la liaison côté gateway");
    }

    @Test
    void aChannelClosedByTheGatewayEndsTheLoop() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.failWith = new PollingTransport.ChannelClosedException("Liaison fermée (409)");
        PollingConnection connection = new PollingConnection(transport, config(), new Console());
        Thread loop = new Thread(connection::run, "polling-test");

        loop.start();
        loop.join(5_000);

        // Repoller après un coupe-circuit ne servirait à rien : le runner s'arrête proprement.
        assertFalse(loop.isAlive());
        assertTrue(transport.disconnected.get());
    }
}
