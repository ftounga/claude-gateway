package fr.claudegateway.atelier.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Les trois budgets de temps de l'arrêt doivent rester cohérents (F-84 / SF-84-08).
 *
 * <p>Le drainage d'un tour repose sur trois valeurs posées dans deux fichiers différents, dont un
 * manifeste Kubernetes que rien ne compile :</p>
 *
 * <pre>
 *   app.shutdown.turn-drain-seconds (300)
 *     &lt; spring.lifecycle.timeout-per-shutdown-phase (310)   — sinon Spring coupe la phase
 *                                                                avant la fin du drainage
 *   2 × timeout-per-shutdown-phase (620)
 *     &lt; terminationGracePeriodSeconds (660)                 — sinon SIGKILL tombe avant
 *                                                                la fin de l'arrêt
 * </pre>
 *
 * <p>Une seule de ces valeurs baissée sans les autres, et le drainage redevient silencieusement
 * inopérant : le code compilerait, les tests unitaires passeraient, et la production
 * recommencerait à perdre des tours. C'est exactement la forme du défaut de F-77, où le correctif
 * appliqué à la main sur le cluster n'existait dans aucun fichier. D'où ce test, qui lit les
 * fichiers eux-mêmes.</p>
 */
class ShutdownBudgetCoherenceTest {

    /** Le dépôt, que les tests soient lancés depuis `backend/` ou depuis la racine. */
    private static Path repoFile(String relative) {
        Path fromBackend = Path.of("..").resolve(relative).normalize();
        return Files.exists(fromBackend) ? fromBackend : Path.of(relative);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static int firstInt(String haystack, String regex, String what) {
        Matcher matcher = Pattern.compile(regex).matcher(haystack);
        assertThat(matcher.find()).as("réglage introuvable : " + what).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    @Test
    void lesTroisBudgetsDarretSemboitent() throws IOException {
        Path applicationYml = repoFile("backend/src/main/resources/application.yml");
        Path deploymentYaml = repoFile("k8s/base/backend/deployment.yaml");
        assumeThat(Files.exists(applicationYml) && Files.exists(deploymentYaml))
                .as("test de cohérence de fichiers : sans les fichiers, rien à vérifier")
                .isTrue();

        String config = read(applicationYml);
        String manifest = read(deploymentYaml);

        assertThat(config)
                .as("l'arrêt gracieux du serveur web est ce qui laisse finir les flux en vol")
                .contains("shutdown: graceful");

        int drain = firstInt(config,
                "turn-drain-seconds:\\s*\\$\\{APP_SHUTDOWN_TURN_DRAIN_SECONDS:(\\d+)}",
                "app.shutdown.turn-drain-seconds");
        int phase = firstInt(config,
                "timeout-per-shutdown-phase:\\s*(\\d+)s",
                "spring.lifecycle.timeout-per-shutdown-phase");
        int grace = firstInt(manifest,
                "terminationGracePeriodSeconds:\\s*(\\d+)",
                "terminationGracePeriodSeconds");

        assertThat(phase)
                .as("une phase plus courte que le drainage rend awaitTerminationSeconds inopérant")
                .isGreaterThan(drain);
        assertThat(grace)
                .as("SIGKILL doit tomber APRÈS les deux phases bornées (web, puis pool)")
                .isGreaterThan(2 * phase);
    }

    @Test
    void lesPodsSontRemplacesUnParUnPendantQuIlsDrainent() throws IOException {
        Path deploymentYaml = repoFile("k8s/base/backend/deployment.yaml");
        assumeThat(Files.exists(deploymentYaml)).isTrue();

        String manifest = read(deploymentYaml);

        assertThat(manifest)
                .as("deux drainages de 5 minutes en parallèle laisseraient la production à zéro")
                .contains("maxUnavailable: 0")
                .contains("maxSurge: 1");
    }
}
