package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Application du filtre d'exclusion par <b>les quatre outils fichiers</b> (F-38 / SF-38-10) : le
 * piège serait de ne filtrer que le listing. Ces tests vérifient que deviner le chemin d'un fichier
 * exclu ne le rend ni lisible ({@code read_file}), ni inscriptible ({@code write_file}), ni
 * atteignable par la recherche ({@code search_files}).
 */
class ExclusionEnforcementTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    private FileTools tools;
    private BlockingQueue<String> frames;
    private FrameSender sender;
    private ToolDispatcher dispatcher;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(root.resolve(".env"), "ANTHROPIC_API_KEY=secret");
        Files.writeString(root.resolve("CLAUDE.md"), "conventions du projet");
        Files.createDirectories(root.resolve(".claude/skills"));
        Files.writeString(root.resolve(".claude/skills/revue.md"), "règles de revue");
        Files.createDirectories(root.resolve(".ssh"));
        Files.writeString(root.resolve(".ssh/id_rsa"), "-----BEGIN PRIVATE KEY----- secret");
        Files.createDirectories(root.resolve("infra"));
        Files.writeString(root.resolve("infra/tls.pem"), "certificat secret");
        Files.writeString(root.resolve("src.txt"), "code visible");

        PathGuard guard = new PathGuard(root, ExclusionRules.load(root, null));
        tools = new FileTools(guard);
        frames = new LinkedBlockingQueue<>();
        sender = new FrameSender(new Console());
        sender.attach(frame -> {
            frames.add(frame);
            return CompletableFuture.completedFuture(null);
        });
        dispatcher = new ToolDispatcher(tools, sender, new Console());
    }

    @AfterEach
    void tearDown() {
        dispatcher.close();
        sender.close();
    }

    // ---------------------------------------------------------------- read_file

    @Test
    void refuseDeLireUnFichierExcluMemeSiLeCheminEstDevine() {
        for (String path : new String[] {".env", ".ssh/id_rsa", "infra/tls.pem"}) {
            ToolOutcome outcome = tools.execute("read_file", input("path", path));

            assertFalse(outcome.ok(), path);
            assertEquals("excluded", outcome.errorCode(), path);
            assertTrue(outcome.errorMessage().contains(path), path);
            assertFalse(outcome.errorMessage().contains(root.toString()), "aucun chemin absolu");
        }
    }

    @Test
    void litToujoursLesFichiersDAmorcage() {
        assertTrue(tools.execute("read_file", input("path", "CLAUDE.md")).ok());
        assertTrue(tools.execute("read_file", input("path", ".claude/skills/revue.md")).ok());
    }

    // ---------------------------------------------------------------- write_file

    @Test
    void refuseDEcrireSurUnCheminExcluSansToucherAuDisque() throws IOException {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", ".ssh/authorized_keys");
        input.put("content", "ssh-rsa AAAA");

        ToolOutcome outcome = tools.execute("write_file", input);

        assertFalse(outcome.ok());
        assertEquals("excluded", outcome.errorCode());
        assertFalse(Files.exists(root.resolve(".ssh/authorized_keys")));
    }

    @Test
    void refuseDeCreerUnDossierExcluAbsent() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", ".aws/credentials");
        input.put("content", "[default]");

        ToolOutcome outcome = tools.execute("write_file", input);

        assertFalse(outcome.ok());
        assertEquals("excluded", outcome.errorCode());
        assertFalse(Files.exists(root.resolve(".aws")), "aucun dossier parent créé");
    }

    // ---------------------------------------------------------------- list_files / search_files

    @Test
    void masqueLesFichiersExclusDuListing() {
        ToolOutcome outcome = tools.execute("list_files", MAPPER.createObjectNode());

        assertTrue(outcome.ok());
        String content = outcome.content();
        assertFalse(content.contains(".env"), content);
        assertFalse(content.contains(".ssh/"), content);
        assertFalse(content.contains("tls.pem"), content);
        assertTrue(content.contains("CLAUDE.md"), content);
        assertTrue(content.contains(".claude/skills/revue.md"), content);
        assertTrue(content.contains("src.txt"), content);
    }

    @Test
    void neRemonteAucuneLigneDUnFichierExcluDansLaRecherche() {
        ToolOutcome outcome = tools.execute("search_files", input("query", "secret"));

        assertTrue(outcome.ok());
        assertEquals("Aucun résultat.", outcome.content());
    }

    @Test
    void trouveToujoursDansLesFichiersNonExclus() {
        ToolOutcome outcome = tools.execute("search_files", input("query", "conventions"));

        assertTrue(outcome.ok());
        assertTrue(outcome.content().startsWith("CLAUDE.md:1: "), outcome.content());
    }

    // ---------------------------------------------------------------- règles utilisateur

    @Test
    void appliqueLesReglesDuRunnerignore() throws IOException {
        Files.writeString(root.resolve(".runnerignore"), "*.txt\n!.env\n");
        FileTools filtered = new FileTools(new PathGuard(root, ExclusionRules.load(root, null)));

        assertEquals("excluded", filtered.execute("read_file", input("path", "src.txt")).errorCode());
        assertEquals("excluded", filtered.execute("read_file", input("path", ".env")).errorCode(),
                "la deny-list gagne toujours sur une négation");
        assertTrue(filtered.execute("read_file", input("path", "CLAUDE.md")).ok());
    }

    // ---------------------------------------------------------------- priorité du confinement

    @Test
    void leConfinementResteProritaireSurLExclusion() {
        PathGuard guard = new PathGuard(root, ExclusionRules.load(root, null));

        ToolException outside = assertThrows(ToolException.class, () -> guard.resolve("../.env"));
        assertEquals("path_outside_root", outside.code());

        ToolException excluded = assertThrows(ToolException.class, () -> guard.resolve(".env"));
        assertEquals("excluded", excluded.code());
    }

    // ---------------------------------------------------------------- bout en bout du canal

    @Test
    void leCanalRepondUnSeulToolResultExcluded() throws Exception {
        ObjectNode call = MAPPER.createObjectNode();
        call.put("type", "tool_call");
        call.put("id", "toolu_excl");
        call.put("tool", "read_file");
        call.set("input", input("path", ".ssh/id_rsa"));
        call.put("timeoutMs", 30_000);

        dispatcher.onToolCall(call);

        JsonNode result = MAPPER.readTree(nextFrame());
        assertEquals("tool_result", result.path("type").asText());
        assertEquals("toolu_excl", result.path("id").asText());
        assertFalse(result.path("ok").asBoolean());
        assertEquals("excluded", result.path("error").path("code").asText());
        assertTrue(result.path("durationMs").asLong() >= 0);
        assertNull(frames.poll(200, TimeUnit.MILLISECONDS), "exactement une trame terminale");
    }

    private String nextFrame() throws InterruptedException {
        String frame = frames.poll(5, TimeUnit.SECONDS);
        assertNotNull(frame, "aucune trame émise");
        return frame;
    }

    private static ObjectNode input(String field, String value) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(field, value);
        return node;
    }
}
