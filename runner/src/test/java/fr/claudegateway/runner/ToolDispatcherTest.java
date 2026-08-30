package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Bout en bout du canal d'outils côté runner, sans réseau (F-38 / SF-38-04) :
 * trame {@code tool_call} → exécution réelle sur disque → trame {@code tool_result} sérialisée,
 * en passant par la vraie file d'émission. Couvre aussi l'annulation, le timeout et les erreurs de
 * protocole.
 */
class ToolDispatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    private BlockingQueue<String> frames;
    private FrameSender sender;
    private ToolDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        frames = new LinkedBlockingQueue<>();
        sender = new FrameSender(new Console());
        sender.attach(frame -> {
            frames.add(frame);
            return CompletableFuture.completedFuture(null);
        });
        dispatcher = new ToolDispatcher(new FileTools(new PathGuard(root)), sender, new Console());
    }

    @AfterEach
    void tearDown() {
        dispatcher.close();
        sender.close();
    }

    @Test
    void repondUnToolResultAuMemeIdentifiant() throws Exception {
        Files.writeString(root.resolve("a.txt"), "bonjour");

        dispatcher.onToolCall(toolCall("toolu_01", "read_file", input("path", "a.txt"), 30_000));

        JsonNode result = nextFrame();
        assertEquals("tool_result", result.path("type").asText());
        assertEquals("toolu_01", result.path("id").asText());
        assertTrue(result.path("ok").asBoolean());
        assertEquals("bonjour", result.path("content").asText());
        assertFalse(result.path("truncated").asBoolean());
        assertTrue(result.path("durationMs").asLong() >= 0);
        assertEquals(7, result.path("bytes").asLong());
    }

    @Test
    void ecritReellementLeFichierDemande() throws Exception {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "src/App.java");
        input.put("content", "class App {}");

        dispatcher.onToolCall(toolCall("toolu_02", "write_file", input, 30_000));

        assertTrue(nextFrame().path("ok").asBoolean());
        assertEquals("class App {}", Files.readString(root.resolve("src/App.java")));
    }

    @Test
    void refuseUnCheminHorsRacineSansToucherAuDisque() throws Exception {
        dispatcher.onToolCall(toolCall("toolu_03", "read_file", input("path", "../../etc/passwd"), 30_000));

        JsonNode result = nextFrame();
        assertFalse(result.path("ok").asBoolean());
        assertEquals("path_outside_root", result.path("error").path("code").asText());
        assertFalse(result.path("error").path("message").asText().contains(root.toString()));
    }

    @Test
    void refuseLOutilBashQuandLaMachineNeLAutorisePas() throws Exception {
        dispatcher.onToolCall(toolCall("toolu_04", "bash", input("command", "ls"), 120_000));

        JsonNode result = nextFrame();
        assertFalse(result.path("ok").asBoolean());
        assertEquals("unsupported_tool", result.path("error").path("code").asText());
    }

    @Test
    void signaleUneEnveloppeSansIdentifiant() throws Exception {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("type", "tool_call");
        frame.put("tool", "read_file");

        dispatcher.onToolCall(frame);

        JsonNode error = nextFrame();
        assertEquals("protocol_error", error.path("type").asText());
        assertEquals("invalid_envelope", error.path("code").asText());
    }

    @Test
    void signaleUneEnveloppeSansOutil() throws Exception {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("type", "tool_call");
        frame.put("id", "toolu_05");

        dispatcher.onToolCall(frame);

        JsonNode error = nextFrame();
        assertEquals("protocol_error", error.path("type").asText());
        assertEquals("toolu_05", error.path("id").asText());
    }

    @Test
    void ignoreUneAnnulationSurUnIdentifiantInconnu() throws Exception {
        ObjectNode cancel = MAPPER.createObjectNode();
        cancel.put("type", "tool_cancel");
        cancel.put("id", "toolu_inconnu");

        dispatcher.onToolCancel(cancel);

        assertNull(frames.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void annuleUnAppelEnVolEtNEmetQuUneSeuleTrameTerminale() throws Exception {
        ToolDispatcher slow = slowDispatcher();
        try {
            slow.onToolCall(toolCall("toolu_06", "read_file", input("path", "a.txt"), 30_000));
            Thread.sleep(100);

            ObjectNode cancel = MAPPER.createObjectNode();
            cancel.put("type", "tool_cancel");
            cancel.put("id", "toolu_06");
            cancel.put("reason", "user_interrupt");
            slow.onToolCancel(cancel);

            JsonNode result = nextFrame();
            assertFalse(result.path("ok").asBoolean());
            assertEquals("cancelled", result.path("error").path("code").asText());
            assertNull(frames.poll(500, TimeUnit.MILLISECONDS), "Une seule trame terminale par id");
        } finally {
            slow.close();
        }
    }

    @Test
    void termineEnTimeoutQuandLeDelaiEstDepasse() throws Exception {
        ToolDispatcher slow = slowDispatcher();
        try {
            slow.onToolCall(toolCall("toolu_07", "read_file", input("path", "a.txt"), 50));

            JsonNode result = nextFrame();
            assertFalse(result.path("ok").asBoolean());
            assertEquals("timeout", result.path("error").path("code").asText());
        } finally {
            slow.close();
        }
    }

    @Test
    void ignoreUnIdentifiantDejaEnVol() throws Exception {
        ToolDispatcher slow = slowDispatcher();
        try {
            slow.onToolCall(toolCall("toolu_08", "read_file", input("path", "a.txt"), 30_000));
            slow.onToolCall(toolCall("toolu_08", "read_file", input("path", "a.txt"), 30_000));

            ObjectNode cancel = MAPPER.createObjectNode();
            cancel.put("type", "tool_cancel");
            cancel.put("id", "toolu_08");
            slow.onToolCancel(cancel);

            assertEquals("cancelled", nextFrame().path("error").path("code").asText());
            assertNull(frames.poll(500, TimeUnit.MILLISECONDS));
        } finally {
            slow.close();
        }
    }

    @Test
    void annonceLaCapaciteFichiersDansLaTrameReady() throws Exception {
        JsonNode ready = MAPPER.readTree(dispatcher.readyFrame("1.2.3"));

        assertEquals("ready", ready.path("type").asText());
        assertEquals(1, ready.path("protocol").asInt());
        assertEquals("1.2.3", ready.path("runnerVersion").asText());
        assertEquals(1, ready.path("capabilities").size());
        assertEquals("files", ready.path("capabilities").get(0).asText());
    }

    @Test
    void annonceLaCapaciteBashQuandLaMachineLAutorise() throws Exception {
        PathGuard guard = new PathGuard(root);
        ToolRouter tools = new ToolRouter(new FileTools(guard), new BashTool(guard, true));
        try (ToolDispatcher withBash = new ToolDispatcher(tools, tools.capabilities(), sender,
                new Console())) {
            JsonNode ready = MAPPER.readTree(withBash.readyFrame("1.2.3"));

            assertEquals(2, ready.path("capabilities").size());
            assertEquals("files", ready.path("capabilities").get(0).asText());
            assertEquals("bash", ready.path("capabilities").get(1).asText());
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void diffuseLaSortieDeBashAvantSaTrameTerminale() throws Exception {
        PathGuard guard = new PathGuard(root);
        ToolRouter tools = new ToolRouter(new FileTools(guard), new BashTool(guard, true));
        try (ToolDispatcher withBash = new ToolDispatcher(tools, tools.capabilities(), sender,
                new Console())) {
            withBash.onToolCall(toolCall("toolu_bash", "bash",
                    input("command", "echo un; echo deux 1>&2"), 30_000));

            JsonNode first = nextFrame();
            JsonNode second = nextFrame();
            JsonNode terminal = nextFrame();

            assertEquals("tool_stream", first.path("type").asText());
            assertEquals("toolu_bash", first.path("id").asText());
            assertEquals(0, first.path("seq").asInt());
            assertEquals("tool_stream", second.path("type").asText());
            // Compteur PARTAGÉ entre stdout et stderr : l'ordre des seq est l'ordre réel (contrat §2.3).
            assertEquals(1, second.path("seq").asInt());
            assertNotEquals(first.path("stream").asText(), second.path("stream").asText());

            assertEquals("tool_result", terminal.path("type").asText());
            assertTrue(terminal.path("ok").asBoolean());
            assertEquals(0, terminal.path("exitCode").asInt());
            assertEquals("", terminal.path("content").asText());
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void uneAnnulationDeBashTueLeProcessusEtNeProduitQuUneTrameTerminale() throws Exception {
        PathGuard guard = new PathGuard(root);
        ToolRouter tools = new ToolRouter(new FileTools(guard), new BashTool(guard, true));
        try (ToolDispatcher withBash = new ToolDispatcher(tools, tools.capabilities(), sender,
                new Console())) {
            withBash.onToolCall(toolCall("toolu_kill", "bash", input("command", "sleep 30"), 30_000));
            Thread.sleep(300);

            ObjectNode cancel = MAPPER.createObjectNode();
            cancel.put("type", "tool_cancel");
            cancel.put("id", "toolu_kill");
            cancel.put("reason", "user_interrupt");
            withBash.onToolCancel(cancel);

            JsonNode terminal = nextFrame();
            assertEquals("tool_result", terminal.path("type").asText());
            assertFalse(terminal.path("ok").asBoolean());
            assertEquals("cancelled", terminal.path("error").path("code").asText());
            assertNull(frames.poll(1, TimeUnit.SECONDS),
                    "Exactement une trame terminale par identifiant");
        }
    }

    @Test
    void abandonneLesAppelsEnVolQuandLaSocketTombe() throws Exception {
        ToolDispatcher slow = slowDispatcher();
        try {
            slow.onToolCall(toolCall("toolu_09", "read_file", input("path", "a.txt"), 30_000));
            Thread.sleep(100);

            slow.abortAll();

            assertNull(frames.poll(500, TimeUnit.MILLISECONDS),
                    "Aucun résultat n'est émis sur une socket perdue (pas de rejeu)");
        } finally {
            slow.close();
        }
    }

    /** Dispatcher branché sur un outil volontairement lent, pour tester annulation et timeout. */
    private ToolDispatcher slowDispatcher() throws IOException {
        Files.writeString(root.resolve("a.txt"), "x");
        ToolExecutor slowTool = (tool, input, context) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolOutcome.error("cancelled", "Appel interrompu.");
            }
            return ToolOutcome.ok("terminé");
        };
        return new ToolDispatcher(slowTool, sender, new Console());
    }

    private JsonNode nextFrame() throws Exception {
        String frame = frames.poll(5, TimeUnit.SECONDS);
        assertNotNull(frame, "Une trame était attendue");
        return MAPPER.readTree(frame);
    }

    private static ObjectNode toolCall(String id, String tool, ObjectNode input, int timeoutMs) {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("type", "tool_call");
        frame.put("id", id);
        frame.put("tool", tool);
        frame.set("input", input);
        frame.put("timeoutMs", timeoutMs);
        return frame;
    }

    private static ObjectNode input(String field, String value) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(field, value);
        return node;
    }
}
