package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.teams.TeamsTools;

/**
 * F-87 / SF-87-03 — le volet Teams dans l'aiguilleur du runner.
 *
 * <p>Deux choses sont tenues ici, et elles se répondent : la capacité {@code teams} n'est annoncée
 * à la gateway que si la machine l'autorise, et un appel {@code teams_*} sur un runner qui ne l'a
 * pas est <b>refusé</b> — jamais servi à moitié par un autre outil.</p>
 */
class TeamsRoutingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    @Test
    @DisplayName("La capacité « teams » n'est annoncée que si la machine l'autorise")
    void the_teams_capability_is_declared_only_when_allowed() {
        assertEquals(List.of("files", "bash"), router(null).capabilities());
        assertEquals(List.of("files", "bash", "teams"), router(activeTools()).capabilities());
        assertEquals(List.of("files", "bash"),
                router(TeamsTools.disabled("--no-teams")).capabilities());
    }

    @Test
    @DisplayName("Un appel teams_* est aiguillé vers le volet Teams, jamais vers les fichiers")
    void teams_calls_go_to_the_teams_tools() {
        ToolOutcome outcome = router(TeamsTools.disabled("--no-teams"))
                .execute("teams_status", MAPPER.createObjectNode(), ToolContext.none());

        assertTrue(outcome.ok(), "un volet désactivé rend un ÉTAT, pas une panne");
        assertTrue(outcome.content().contains("BROWSER_NOT_DETECTED"), outcome.content());
        assertTrue(outcome.content().contains("--no-teams"), outcome.content());
    }

    @Test
    @DisplayName("Sur un runner sans volet Teams, l'appel est refusé et le dit")
    void without_teams_the_call_is_refused() {
        ToolOutcome outcome = router(null).execute("teams_status", MAPPER.createObjectNode(),
                ToolContext.none());

        assertFalse(outcome.ok());
        assertEquals("unsupported_tool", outcome.errorCode());
        assertTrue(outcome.errorMessage().contains("n'est pas actif sur cette machine"));
    }

    private static TeamsTools activeTools() {
        return new TeamsTools(new fr.claudegateway.runner.teams.TeamsSession(9222,
                fr.claudegateway.runner.teams.TeamsAdapters.current(), message -> { }),
                millis -> { });
    }

    private ToolRouter router(TeamsTools teams) {
        PathResolver guard = new PathResolver(root);
        return new ToolRouter(new FileTools(guard),
                new BashTool(guard, true, ShellElection.elect()), teams);
    }
}
