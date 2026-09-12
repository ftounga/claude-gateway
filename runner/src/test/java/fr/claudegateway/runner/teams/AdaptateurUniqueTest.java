package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * F-87 / SF-87-01 — <b>le test qui tient la règle de l'adaptateur unique</b>.
 *
 * <p>« Tout ce que le produit sait de Teams vit dans une seule couche » est une intention tant que
 * personne ne la vérifie. Ce test la rend contraignante : aucun fichier du runner, hors du paquet
 * {@code teams}, ne peut contenir une adresse Microsoft ni un nom de champ de leurs réponses.</p>
 *
 * <p>Ce n'est pas une coquetterie d'architecture. Le jour où Microsoft changera quelque chose, la
 * promesse du cadrage — <b>un seul endroit à corriger</b> — ne tiendra que si elle a été tenue tous
 * les jours d'ici là. Une seule fuite, et la réparation devient une chasse.</p>
 */
@EnabledIf("sourcesArePresent")
class AdaptateurUniqueTest {

    /** Ce que seul l'adaptateur a le droit de connaître. */
    private static final List<String> TEAMS_KNOWLEDGE = List.of(
            "teams.microsoft.com", "teams.live.com", "chatsvc", "skype",
            "imdisplayname", "originalarrivaltime", "messagetype", "conversationlink",
            "activityfeed", "sourcethreadid", "threadproperties");

    static boolean sourcesArePresent() {
        return Files.isDirectory(sources());
    }

    private static Path sources() {
        return Paths.get("src", "main", "java");
    }

    @Test
    @DisplayName("Hors du paquet teams, aucun fichier ne sait ce qu'est Teams")
    void no_file_outside_the_adapter_knows_teams() throws IOException {
        List<String> offences = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources())) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.toString().replace('\\', '/').contains("/runner/teams/")) {
                    continue; // la couche unique — c'est précisément son rôle
                }
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT);
                for (String knowledge : TEAMS_KNOWLEDGE) {
                    if (text.contains(knowledge)) {
                        offences.add(file.getFileName() + " : « " + knowledge + " »");
                    }
                }
            }
        }
        assertTrue(offences.isEmpty(),
                "Le savoir Teams a fui hors de l'adaptateur unique : " + offences);
    }
}
