package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F-38 / SF-38-26 — le montage de la pile d'outils ne redit plus l'état de l'exécution de commandes.
 *
 * <p>Le défaut corrigé : {@code RunnerMain} l'annonçait au démarrage, puis {@code ToolStack} le
 * réannonçait à l'ouverture du canal — deux formulations, deux vocabulaires, la seconde fausse. Et
 * {@code ToolStack.create} étant appelé <b>une fois par transport</b>, le repli long-polling en
 * ajoutait une troisième.</p>
 */
class ToolStackAnnouncementTest {

    @TempDir
    Path workspace;

    @Test
    void mounting_the_stack_says_nothing_about_the_execution_state() {
        for (boolean restricted : new boolean[] {false, true}) {
            String announced = String.join("\n", mount(restricted));

            assertFalse(announced.contains("xécution de commandes"),
                    "l'état d'exécution appartient à RunnerMain seul (D1) : " + announced);
            assertFalse(announced.contains("Commandes :"), announced);
        }
    }

    @Test
    void mounting_the_stack_never_mentions_the_flag_that_does_nothing() {
        for (boolean restricted : new boolean[] {false, true}) {
            String announced = String.join("\n", mount(restricted));

            assertFalse(announced.contains("allow-bash"),
                    "--allow-bash n'a plus d'effet depuis SF-38-19 : " + announced);
        }
    }

    @Test
    void mounting_the_stack_still_announces_root_shell_and_exclusions() {
        // Ces trois lignes restent, et restent répétées à chaque transport : elles attestent que le
        // repli long-polling monte les mêmes gardes que la socket (D1).
        String announced = String.join("\n", mount(false));

        assertTrue(announced.contains("Outils fichiers actifs"), announced);
        assertTrue(announced.contains("Interpréteur :"), announced);
        assertTrue(announced.contains("Exclusions :"), announced);
    }

    /** Monte la pile sur un workspace vide et rend les lignes que la console a reçues. */
    private List<String> mount(boolean restricted) {
        List<String> lines = new ArrayList<>();
        Console console = new Console(lines::add, ConsoleEncoding.forCharset(StandardCharsets.UTF_8));
        RunnerConfig config = RunnerConfig.resolve(new String[] {
                "--gateway", "https://gw.example.com/api",
                "--workspace", workspace.toString()
        }, restricted ? Map.of("CLAUDE_RUNNER_NO_BASH", "true") : Map.of());

        try (FrameSender sender = new FrameSender(console)) {
            ToolStack.create(config, console, sender);
        }
        return lines;
    }
}
