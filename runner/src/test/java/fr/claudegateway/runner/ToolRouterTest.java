package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Aiguillage des outils du runner (F-38 / SF-38-07) et capacités annoncées. */
class ToolRouterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    @Test
    void annonceBashUniquementQuandLaMachineLaAutorise() {
        assertEquals(List.of("files"), router(false).capabilities());
        assertEquals(List.of("files", "bash"), router(true).capabilities());
    }

    @Test
    void aiguilleLesOutilsFichiersVersFileTools() throws IOException {
        Files.writeString(root.resolve("a.txt"), "bonjour");

        ToolOutcome outcome = router(true).execute("read_file",
                MAPPER.createObjectNode().put("path", "a.txt"), ToolContext.none());

        assertTrue(outcome.ok());
        assertEquals("bonjour", outcome.content());
    }

    @Test
    void aiguilleBashVersLeBashTool() {
        // Routeur bash désactivé : le refus prouve que l'appel est bien parti au BashTool, et pas
        // au FileTools (qui répondrait « unsupported_tool » avec un autre message).
        ToolOutcome outcome = router(false).execute("bash",
                MAPPER.createObjectNode().put("command", "echo x"), ToolContext.none());

        assertFalse(outcome.ok());
        assertEquals("unsupported_tool", outcome.errorCode());
        assertTrue(outcome.errorMessage().contains("pas activée"));
    }

    @Test
    void unOutilInconnuResteRefuseParLesOutilsFichiers() {
        ToolOutcome outcome = router(true).execute("deploy", MAPPER.createObjectNode(),
                ToolContext.none());

        assertFalse(outcome.ok());
        assertEquals("unsupported_tool", outcome.errorCode());
    }

    private ToolRouter router(boolean allowBash) {
        PathGuard guard = new PathGuard(root);
        return new ToolRouter(new FileTools(guard), new BashTool(guard, allowBash));
    }
}
