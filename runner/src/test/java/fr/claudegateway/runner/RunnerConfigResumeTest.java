package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Redémarrage sans argument (F-46 / SF-46-01) : la mémoire complète ce qui manque, et rien de plus.
 */
class RunnerConfigResumeTest {

    @TempDir
    Path workspace;

    @TempDir
    Path home;

    private SessionMemory.Located remembered() {
        SessionMemory.remember(
                new SessionMemory("https://portal.example.com/api", workspace.toString(),
                        OffsetDateTime.now()),
                workspace, null);
        return SessionMemory.locate(workspace, home).orElseThrow();
    }

    @Test
    @DisplayName("aucun argument : passerelle et racine viennent de la mémoire")
    void resolves_with_no_arguments_at_all() {
        RunnerConfig config = RunnerConfig.resolve(new String[] {}, Map.of(), remembered());

        assertEquals("https://portal.example.com/api", config.gatewayBaseUrl());
        assertEquals(workspace.toAbsolutePath().normalize(), config.hostRoot());
        assertEquals(SessionMemory.fileIn(workspace), config.resumedFrom());
        assertNull(config.pairingCode(), "une reprise n'invente aucun code d'appairage");
    }

    @Test
    @DisplayName("la ligne de commande l'emporte sur la mémoire")
    void cli_wins_over_memory(@TempDir Path other) {
        RunnerConfig config = RunnerConfig.resolve(new String[] {
                "--gateway", "https://impose.example.com/api",
                "--workspace", other.toString()
        }, Map.of(), remembered());

        assertEquals("https://impose.example.com/api", config.gatewayBaseUrl());
        assertEquals(other.toAbsolutePath().normalize(), config.hostRoot());
        assertNull(config.resumedFrom(), "rien ne vient de la mémoire : ne pas l'annoncer");
    }

    @Test
    @DisplayName("l'environnement aussi l'emporte sur la mémoire")
    void env_wins_over_memory(@TempDir Path other) {
        RunnerConfig config = RunnerConfig.resolve(new String[] {}, Map.of(
                "CLAUDE_RUNNER_WORKSPACE", other.toString()), remembered());

        assertEquals("https://portal.example.com/api", config.gatewayBaseUrl(),
                "la mémoire complète le champ absent");
        assertEquals(other.toAbsolutePath().normalize(), config.hostRoot());
    }

    @Test
    @DisplayName("sans mémoire ni argument : refus nommant les deux gestes")
    void refuses_when_nothing_is_remembered() {
        RunnerConfig.ConfigException error = assertThrows(RunnerConfig.ConfigException.class,
                () -> RunnerConfig.resolve(new String[] {}, Map.of(), null));

        assertTrue(error.getMessage().contains("--gateway est requis"), error.getMessage());
        assertTrue(error.getMessage().contains("Aucune configuration mémorisée"), error.getMessage());
        assertTrue(error.getMessage().contains("Connecter une machine"), error.getMessage());
    }

    @Test
    @DisplayName("racine mémorisée introuvable : refus, aucune racine devinée")
    void refuses_when_the_remembered_root_is_gone(@TempDir Path elsewhere) {
        SessionMemory.remember(
                new SessionMemory("https://portal.example.com/api",
                        home.resolve("projet-supprime").toString(), OffsetDateTime.now()),
                null, home);
        SessionMemory.Located located = SessionMemory.locate(elsewhere, home).orElseThrow();

        RunnerConfig.ConfigException error = assertThrows(RunnerConfig.ConfigException.class,
                () -> RunnerConfig.resolve(new String[] {}, Map.of(), located));

        assertTrue(error.getMessage().contains("projet-supprime"), error.getMessage());
        assertTrue(error.getMessage().contains("--workspace"), error.getMessage());
    }

    @Test
    @DisplayName("une mémoire ne dispense d'aucune validation")
    void memory_is_validated_like_a_command_line() {
        SessionMemory.Located forged = new SessionMemory.Located(
                new SessionMemory("pas-une-url", workspace.toString(), null),
                SessionMemory.fileIn(workspace), true);

        RunnerConfig.ConfigException error = assertThrows(RunnerConfig.ConfigException.class,
                () -> RunnerConfig.resolve(new String[] {}, Map.of(), forged));

        assertTrue(error.getMessage().contains("URL absolue"), error.getMessage());
    }

    @Test
    @DisplayName("les commandes d'hier gardent leur comportement")
    void legacy_command_lines_are_untouched() {
        RunnerConfig config = RunnerConfig.resolve(new String[] {
                "--gateway", "https://portal.example.com/api",
                "--workspace", workspace.toString(),
                "--code", "AB2C3D4E"
        }, Map.of());

        assertEquals("AB2C3D4E", config.pairingCode());
        assertNull(config.resumedFrom());
    }
}
