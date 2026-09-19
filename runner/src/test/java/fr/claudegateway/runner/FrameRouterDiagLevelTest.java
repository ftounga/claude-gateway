package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.diag.RunnerDiag;
import fr.claudegateway.runner.diag.RunnerDiagLevel;

/**
 * F-132 / SF-132-05 — l'aiguilleur applique une commande {@code runner_diag_level} au collecteur, et
 * ignore proprement une trame incomplète (compat ascendante).
 */
class FrameRouterDiagLevelTest {

    private FrameSender sender;

    @BeforeEach
    void setUp() {
        RunnerDiag.reset();
        sender = new FrameSender(new Console());
    }

    @AfterEach
    void tearDown() {
        sender.close();
        RunnerDiag.reset();
    }

    private void route(String payload) {
        try (ToolDispatcher dispatcher = new ToolDispatcher(
                (tool, input, context) -> ToolOutcome.ok(""), sender, new Console())) {
            new FrameRouter(dispatcher, new Console()).route(payload);
        }
    }

    @Test
    @DisplayName("Une commande runner_diag_level règle le niveau (DEBUG)")
    void sets_level_from_command() {
        route("{\"type\":\"runner_diag_level\",\"level\":\"DEBUG\",\"ttlSeconds\":600}");
        assertEquals(RunnerDiagLevel.DEBUG, RunnerDiag.level());
    }

    @Test
    @DisplayName("Une commande sans niveau lisible ne change rien (compat ascendante)")
    void unknown_level_changes_nothing() {
        route("{\"type\":\"runner_diag_level\",\"level\":\"NONSENSE\"}");
        assertEquals(RunnerDiagLevel.INFO, RunnerDiag.level());

        route("{\"type\":\"runner_diag_level\"}");
        assertEquals(RunnerDiagLevel.INFO, RunnerDiag.level());
    }
}
