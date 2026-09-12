package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * F-73 / SF-73-01 — <b>le test qui garde la phrase</b>.
 *
 * <p>Le défaut que F-73 corrige n'était pas d'abord du code : c'était une phrase. Le commentaire de
 * {@code BashTool} affirmait qu'« une commande ne s'exécute jamais hors de la racine exposée », et
 * cette phrase a été lue, crue, et répétée pendant des jours — jusqu'à ce que le product owner
 * teste. Le code, lui, n'inspectait que le {@code cwd}.</p>
 *
 * <p>Ce test empêche la phrase de revenir. Il ne juge pas la présence du mot « confinement » — les
 * en-têtes actuels l'emploient précisément pour <b>dire qu'il n'y en a plus</b> — mais celle des
 * <b>affirmations</b> qui promettaient une garde inexistante, et de la liste de secrets supprimée.</p>
 *
 * <p>Une <b>citation</b> entre guillemets français ne compte pas : plusieurs en-têtes reprennent la
 * phrase d'origine pour expliquer ce qui a été retiré, et c'est exactement ce qu'on veut garder. Le
 * texte entre {@code «} et {@code »} est donc écarté avant l'examen.</p>
 */
@EnabledIf("sourcesArePresent")
class NoConfinementPromiseTest {

    /** Les affirmations retirées le 2026-09-12, chacune fausse au moment où elle a été écrite. */
    private static final List<String> BANNED = List.of(
            "ne s'exécute jamais hors de la racine",
            "confiné au dossier du projet",
            "chaque tour est confiné",
            "non désactivable (D10)",
            "DEFAULT_DENY");

    static boolean sourcesArePresent() {
        return Files.isDirectory(sources());
    }

    private static Path sources() {
        // Surefire s'exécute à la racine du module ; le test se désactive si le source n'est pas là
        // (jar isolé, exécution depuis un autre répertoire).
        return Paths.get("src", "main", "java");
    }

    @Test
    void no_source_file_promises_a_confinement_that_does_not_exist() throws IOException {
        List<String> offences = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources())) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                String lower = withoutQuotations(text).toLowerCase(java.util.Locale.ROOT);
                for (String banned : BANNED) {
                    if (lower.contains(banned.toLowerCase(java.util.Locale.ROOT))) {
                        offences.add(file.getFileName() + " : « " + banned + " »");
                    }
                }
            }
        }
        assertTrue(offences.isEmpty(),
                "Une promesse de confinement est revenue dans le code du runner : " + offences);
    }

    /** Retire les citations « … » : citer la phrase retirée est documentaire, pas une promesse. */
    private static String withoutQuotations(String text) {
        return text.replaceAll("(?s)«.*?»", " ");
    }
}
