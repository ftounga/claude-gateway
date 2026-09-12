package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Portée réelle du filtre d'exclusion après F-73 / SF-73-01 : il n'élague que le <b>listage</b>.
 *
 * <p>Ce fichier remplace {@code ExclusionEnforcementTest}, qui vérifiait l'inverse — que deviner le
 * chemin d'un fichier exclu ne le rendait ni lisible ni inscriptible. Cette garde a été retirée par
 * le product owner le 2026-09-12 : elle ne tenait que sur les quatre outils fichiers, alors qu'un
 * {@code cat .env} passé à {@code bash} n'a jamais rien rencontré. Les tests disent donc désormais
 * ce que le produit fait vraiment.</p>
 */
class ExclusionListingTest {

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
        Files.createDirectories(root.resolve("node_modules/left-pad"));
        Files.writeString(root.resolve("node_modules/left-pad/index.js"), "module.exports = 1;");
        Files.writeString(root.resolve("src.txt"), "code visible");

        tools = new FileTools(new PathResolver(root, ExclusionRules.load(root, null)));
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

    // ------------------------------------------------ ce que la liste écarte (et pourquoi)

    @Test
    void masqueLeBruitDeConstructionDuListing() {
        ToolOutcome outcome = tools.execute("list_files", MAPPER.createObjectNode());

        assertTrue(outcome.ok());
        String content = outcome.content();
        assertFalse(content.contains("node_modules"), content);
        assertTrue(content.contains("CLAUDE.md"), content);
        assertTrue(content.contains(".claude/skills/revue.md"), content);
        assertTrue(content.contains("src.txt"), content);
    }

    @Test
    void appliqueLesReglesDuRunnerignoreAuListing() throws IOException {
        Files.writeString(root.resolve(".runnerignore"), "*.txt\n");
        FileTools filtered = new FileTools(new PathResolver(root, ExclusionRules.load(root, null)));

        String content = filtered.execute("list_files", MAPPER.createObjectNode()).content();

        assertFalse(content.contains("src.txt"), content);
        assertTrue(content.contains("CLAUDE.md"), content);
    }

    @Test
    void uneNegationReactiveLeBruitDansLeListing() throws IOException {
        Files.writeString(root.resolve(".runnerignore"), "!node_modules/\n");
        FileTools filtered = new FileTools(new PathResolver(root, ExclusionRules.load(root, null)));

        String content = filtered.execute("list_files", MAPPER.createObjectNode()).content();

        assertTrue(content.contains("node_modules/left-pad/index.js"), content);
    }

    // ------------------------------------------------ ce qu'un chemin NOMMÉ atteint quand même

    @Test
    void litUnFichierSensibleQuandIlEstNomme() {
        for (String path : new String[] {".env", ".ssh/id_rsa"}) {
            ToolOutcome outcome = tools.execute("read_file", input("path", path));

            assertTrue(outcome.ok(), path + " : plus aucune exclusion ne s'y oppose (F-73)");
        }
        assertTrue(tools.execute("read_file", input("path", ".env")).content()
                .contains("ANTHROPIC_API_KEY"));
    }

    @Test
    void litUnFichierEcarteDuListingQuandIlEstNomme() {
        ToolOutcome outcome = tools.execute("read_file", input("path", "node_modules/left-pad/index.js"));

        assertTrue(outcome.ok(), "écarté de la LISTE, jamais de la lecture");
    }

    @Test
    void ecritSousUnDossierEcarteDuListing() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", ".ssh/authorized_keys");
        input.put("content", "ssh-rsa AAAA");

        ToolOutcome outcome = tools.execute("write_file", input);

        assertTrue(outcome.ok());
        assertTrue(Files.exists(root.resolve(".ssh/authorized_keys")));
    }

    @Test
    void litToujoursLesFichiersDAmorcage() {
        assertTrue(tools.execute("read_file", input("path", "CLAUDE.md")).ok());
        assertTrue(tools.execute("read_file", input("path", ".claude/skills/revue.md")).ok());
    }

    // ------------------------------------------------ recherche : même règle que le listage

    @Test
    void laRechercheSuitLeMemePerimetreQueLeListing() {
        ToolOutcome outcome = tools.execute("search_files", input("query", "module.exports"));

        assertTrue(outcome.ok());
        assertEquals("Aucun résultat.", outcome.content(), "le bruit reste hors du balayage");
    }

    @Test
    void trouveDansLesFichiersBalayes() {
        ToolOutcome outcome = tools.execute("search_files", input("query", "conventions"));

        assertTrue(outcome.ok());
        assertTrue(outcome.content().startsWith("CLAUDE.md:1: "), outcome.content());
    }

    // ------------------------------------------------ bout en bout du canal

    @Test
    void leCanalRendLeContenuDUnFichierSensible() throws Exception {
        ObjectNode call = MAPPER.createObjectNode();
        call.put("type", "tool_call");
        call.put("id", "toolu_secret");
        call.put("tool", "read_file");
        call.set("input", input("path", ".ssh/id_rsa"));
        call.put("timeoutMs", 30_000);

        dispatcher.onToolCall(call);

        JsonNode result = MAPPER.readTree(nextFrame());
        assertEquals("tool_result", result.path("type").asText());
        assertEquals("toolu_secret", result.path("id").asText());
        assertTrue(result.path("ok").asBoolean(), "plus aucun refus 'excluded' (F-73)");
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
