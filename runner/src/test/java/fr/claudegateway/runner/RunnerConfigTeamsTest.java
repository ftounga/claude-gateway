package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F-87 / SF-87-03 — les deux réglages du volet Teams sur une machine.
 *
 * <p>Le volet est <b>actif par défaut</b>, comme les autres capacités : il ne fait rien tant que
 * personne ne le demande, et il ne peut rien faire sans un navigateur que l'utilisateur a lancé
 * lui-même avec un port de débogage. {@code --no-teams} existe pour la machine où la politique
 * interne l'interdit — et il retire la capacité, il ne la cache pas.</p>
 */
class RunnerConfigTeamsTest {

    @TempDir
    Path workspace;

    private String[] args(String... extra) {
        String[] base = {
                "--gateway", "https://portal.example.com/api",
                "--workspace", workspace.toString(),
                "--code", "AB2C3D4E"
        };
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    @Test
    @DisplayName("Actif par défaut, sur le port 9222")
    void active_by_default() {
        RunnerConfig config = RunnerConfig.resolve(args(), Map.of());

        assertTrue(config.allowTeams());
        assertEquals(9222, config.teamsPort());
    }

    @Test
    @DisplayName("--no-teams retire le volet")
    void no_teams_removes_it() {
        assertFalse(RunnerConfig.resolve(args("--no-teams"), Map.of()).allowTeams());
        assertFalse(RunnerConfig.resolve(args(), Map.of("CLAUDE_RUNNER_NO_TEAMS", "true"))
                .allowTeams());
    }

    @Test
    @DisplayName("--no-teams n'avale pas l'argument suivant")
    void no_teams_is_a_flag() {
        RunnerConfig config = RunnerConfig.resolve(args("--no-teams", "--label", "poste-dev"),
                Map.of());

        assertFalse(config.allowTeams());
        assertEquals("poste-dev", config.label());
    }

    @Test
    @DisplayName("Le port se choisit par l'argument, puis par l'environnement")
    void the_port_can_be_chosen() {
        assertEquals(9333, RunnerConfig.resolve(args("--teams-port", "9333"), Map.of()).teamsPort());
        assertEquals(9444, RunnerConfig.resolve(args(), Map.of("CLAUDE_TEAMS_DEBUG_PORT", "9444"))
                .teamsPort());
        assertEquals(9333, RunnerConfig.resolve(args("--teams-port", "9333"),
                Map.of("CLAUDE_TEAMS_DEBUG_PORT", "9444")).teamsPort(),
                "la ligne de commande prime sur l'environnement");
    }

    @Test
    @DisplayName("Un port illisible ne fait pas échouer le lancement : il retombe sur le défaut")
    void an_unusable_port_falls_back() {
        assertEquals(9222, RunnerConfig.resolve(args("--teams-port", "pas-un-port"), Map.of())
                .teamsPort());
    }
}
