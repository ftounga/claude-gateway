package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * F-38 / SF-38-26 — la console reste lisible sur un terminal qui n'est pas UTF-8.
 *
 * <p>Le défaut corrigé : {@code Appairage aupr?s de https://.../runner/pair?} sur une console
 * Windows cp850, parce que {@code …}, {@code —} et {@code ’} n'y ont aucune correspondance.</p>
 */
class ConsoleEncodingTest {

    /** Page de code des consoles Windows en Europe de l'Ouest. */
    private static final Charset CP850 = Charset.forName("IBM850");

    private static final String TYPOGRAPHIE = "Appairage auprès de https://gw/runner/pair…";

    // --- Terminal UTF-8 : rien ne bouge -----------------------------------------------------

    @Test
    void utf8_leaves_the_message_untouched() {
        ConsoleEncoding utf8 = ConsoleEncoding.forCharset(StandardCharsets.UTF_8);

        // Égalité stricte : la correction ne doit rien coûter à ceux que le défaut n'atteignait pas
        // (Linux, macOS, Windows Terminal).
        assertEquals(TYPOGRAPHIE, utf8.render(TYPOGRAPHIE));
        assertEquals("Arrêt demandé — fermeture de la connexion…",
                utf8.render("Arrêt demandé — fermeture de la connexion…"));
    }

    @Test
    void a_null_message_stays_null() {
        assertNull(ConsoleEncoding.forCharset(CP850).render(null));
    }

    // --- Terminal cp850 : on translittère ce qu'il ne sait pas écrire ------------------------

    @Test
    void cp850_transliterates_what_it_cannot_write() {
        ConsoleEncoding cp850 = ConsoleEncoding.forCharset(CP850);

        assertEquals("Appairage auprès de https://gw/runner/pair...", cp850.render(TYPOGRAPHIE));
        assertEquals("Commandes : refusées (--no-bash) - seuls les outils fichiers.",
                cp850.render("Commandes : refusées (--no-bash) — seuls les outils fichiers."));
        assertEquals("l'écran", cp850.render("l’écran"));
    }

    @Test
    void cp850_keeps_accents_and_french_quotes() {
        ConsoleEncoding cp850 = ConsoleEncoding.forCharset(CP850);

        // C'est ce qui rendait le défaut invisible en relecture : cp850 sait écrire les accents et
        // les guillemets français. Seule la ponctuation typographique tombait.
        assertEquals("Réappairage « poste-dev » réussi",
                cp850.render("Réappairage « poste-dev » réussi"));
    }

    @Test
    void cp850_never_leaves_a_question_mark_where_a_character_was_dropped() {
        ConsoleEncoding cp850 = ConsoleEncoding.forCharset(CP850);

        // Un nom de dossier en idéogrammes : cp850 n'en écrira rien. Le caractère est retiré — pas
        // rendu en `?`, qui est exactement le symptôme que cette classe supprime.
        String rendered = cp850.render("Workspace : projet-東京");
        assertFalse(rendered.contains("?"), "aucun point d'interrogation de substitution");
        assertTrue(rendered.startsWith("Workspace : projet-"));
    }

    // --- Flux US-ASCII : redirection, LANG=C -------------------------------------------------

    @Test
    void ascii_strips_accents_instead_of_printing_question_marks() {
        ConsoleEncoding ascii = ConsoleEncoding.forCharset(StandardCharsets.US_ASCII);

        assertEquals("Reappairage avec le code fourni...",
                ascii.render("Réappairage avec le code fourni…"));
        // Les guillemets français ne passent pas non plus en ASCII : ils ont leur équivalent.
        assertEquals("Interpreteur : <<bash POSIX>>", ascii.render("Interpréteur : «bash POSIX»"));
        assertFalse(ascii.render("Arrêt demandé — connexion…").contains("?"));
    }

    // --- Détection du jeu : ne jamais faire échouer le runner --------------------------------

    @Test
    void the_charset_is_read_from_the_standard_output_property_first() {
        ConsoleEncoding fromProperties = ConsoleEncoding.forSystem(
                Map.of("stdout.encoding", "IBM850", "native.encoding", "UTF-8")::get);

        // stdout.encoding l'emporte : c'est le jeu de la console, pas celui du système de fichiers.
        assertEquals("...", fromProperties.render("…"));
    }

    @Test
    void a_blank_or_unknown_charset_falls_back_without_throwing() {
        // Une console est un confort : elle ne fait pas échouer un runner. Nom vide, puis nom
        // inconnu de la JVM, puis rien du tout — aucune exception, un adaptateur utilisable.
        assertEquals("...", ConsoleEncoding.forSystem(Map.of("stdout.encoding", "   ",
                "sun.stdout.encoding", "charabia-42", "native.encoding", "IBM850")::get)
                .render("…"));
        assertNotNull(ConsoleEncoding.forSystem(key -> null).render("Arrêt demandé…"));
    }

    // --- La console elle-même passe bien par l'adaptateur ------------------------------------

    @Test
    void the_console_renders_through_the_adapter() {
        StringBuilder captured = new StringBuilder();
        Console console = new Console(captured::append, ConsoleEncoding.forCharset(CP850));

        console.info("Appairage réussi — jeton stocké.");

        String line = captured.toString();
        assertTrue(line.contains("Appairage réussi - jeton stocké."), line);
        assertTrue(line.contains("INFO"), line);
    }
}
