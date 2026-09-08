package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ce que le runner dit quand la reprise n'aboutit pas (F-46 / SF-46-01).
 *
 * <p>Un message de refus est un livrable : il est donc testé comme tel. Ce qui compte n'est pas sa
 * formulation exacte mais qu'il nomme <b>le geste</b> — et qu'il ne promette jamais un réappairage
 * qui n'aura pas lieu.</p>
 */
class RunnerResumeMessagesTest {

    @Test
    @DisplayName("sans mémoire, le message nomme les deux gestes possibles")
    void no_memory_names_both_gestures() {
        String message = ResumeMessages.noMemoryHint();

        assertTrue(message.contains("Aucune configuration mémorisée"), message);
        assertTrue(message.contains("sans aucun argument"), message);
        assertTrue(message.contains("Connecter une machine"), message);
    }

    @Test
    @DisplayName("jeton expiré : la date est donnée, et un code est demandé explicitement")
    void expired_token_names_the_date() {
        OffsetDateTime expired = OffsetDateTime.of(2026, 9, 3, 14, 30, 0, 0, ZoneOffset.UTC);

        String message = ResumeMessages.cannotResume(Path.of("/projet/.claude-runner/token.json"),
                expired);

        assertTrue(message.contains("03/09/2026"), message);
        assertTrue(message.contains("expiré"), message);
        assertTrue(message.contains("--code"), message);
    }

    @Test
    @DisplayName("jeton absent : le message le distingue d'un jeton expiré")
    void missing_token_is_not_an_expired_one() {
        String message = ResumeMessages.cannotResume(Path.of("/projet/.claude-runner/token.json"),
                null);

        assertTrue(message.contains("Aucun jeton stocké"), message);
        assertFalse(message.contains("expiré"),
                "un jeton jamais créé n'a pas expiré : le message ne doit pas le dire");
        assertTrue(message.contains("--code"), message);
    }

    @Test
    @DisplayName("racine mémorisée introuvable : le chemin est cité, rien n'est deviné")
    void missing_root_quotes_the_recorded_path() {
        SessionMemory.Located located = new SessionMemory.Located(
                new SessionMemory("https://portal.example.com/api", "/ancien/projet", null),
                Path.of("/home/moi/.claude-runner/session.json"), false);

        String message = ResumeMessages.rootGone(located);

        assertTrue(message.contains("/ancien/projet"), message);
        assertTrue(message.contains("--workspace"), message);
    }

    @Test
    @DisplayName("la ligne de reprise dit d'où vient la configuration")
    void resumed_line_names_the_file() {
        String line = ResumeMessages.resumedFrom(Path.of("/projet/.claude-runner/session.json"));

        assertTrue(line.startsWith("Reprise"), line);
        assertTrue(line.contains("/projet/.claude-runner/session.json"), line);
    }
}
