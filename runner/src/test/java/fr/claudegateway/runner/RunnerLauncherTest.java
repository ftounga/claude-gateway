package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le lanceur (F-38 / SF-38-22) : la seule classe du runner qu'une JVM 8 puisse charger, et donc la
 * seule qui puisse dire à son utilisateur qu'il lui faut Java 21.
 */
class RunnerLauncherTest {

    @Test
    @DisplayName("accepte la version cible et les suivantes")
    void acceptsTwentyOneAndLater() {
        assertEquals(21, RunnerLauncher.currentMajor("21"));
        assertEquals(22, RunnerLauncher.currentMajor("22"));
        assertEquals(25, RunnerLauncher.currentMajor("25"));
    }

    @Test
    @DisplayName("lit les versions anciennes, y compris la forme « 1.8 »")
    void readsLegacyVersions() {
        // C'est la forme exacte que renvoie la JVM du poste client à l'origine de cette subfeature.
        assertEquals(8, RunnerLauncher.currentMajor("1.8"));
        assertEquals(11, RunnerLauncher.currentMajor("11"));
        assertEquals(17, RunnerLauncher.currentMajor("17"));
    }

    @Test
    @DisplayName("une version illisible ne bloque pas le démarrage")
    void anUnreadableVersionLetsTheRunnerStart() {
        // D2 : le doute profite au démarrage. -1 signifie « on ne sait pas », et main() ne refuse
        // que sur une version LUE et inférieure. Une JVM exotique mais valide doit démarrer.
        assertEquals(-1, RunnerLauncher.currentMajor(null));
        assertEquals(-1, RunnerLauncher.currentMajor(""));
        assertEquals(-1, RunnerLauncher.currentMajor("inconnu"));
        assertEquals(-1, RunnerLauncher.currentMajor("0"));
    }

    @Test
    @DisplayName("le message nomme la version trouvée, jamais le format de classe")
    void theMessageNamesTheJavaVersion() {
        String message = RunnerLauncher.message(8);

        assertTrue(message.contains("Java 21"), message);
        assertTrue(message.contains("Java 8"), message);
        // « class file version 52.0 » n'est interprétable que par qui connaît déjà la réponse (D3).
        assertFalse(message.contains("class file"), message);
        assertFalse(message.contains("52"), message);
        // Le message doit être actionnable : vérifier, installer, relancer.
        assertTrue(message.contains("java -version"), message);
        assertTrue(message.contains("adoptium.net"), message);
        assertTrue(message.contains("claude-runner.jar"), message);
    }

    @Test
    @DisplayName("le lanceur est compilé pour Java 8, le reste du runner pour Java 21")
    void theLauncherAloneIsCompiledForJavaEight() throws IOException {
        // Le cœur de la subfeature. Si cette assertion tombe, le runner redevient illisible sur une
        // JVM ancienne : la classe d'entrée ne se chargerait plus, et l'utilisateur retrouverait
        // UnsupportedClassVersionError à la place du message.
        assertEquals(52, classFileVersion("RunnerLauncher"));
        assertEquals(65, classFileVersion("RunnerMain"));
    }

    /** Version majeure du format de classe, lue à l'octet 7 de l'en-tête. */
    private int classFileVersion(String simpleName) throws IOException {
        Path compiled = Path.of("target", "classes", "fr", "claudegateway", "runner",
                simpleName + ".class");
        assertTrue(Files.exists(compiled), "classe compilée introuvable : " + compiled);
        try (InputStream in = Files.newInputStream(compiled)) {
            byte[] header = in.readNBytes(8);
            return header[7] & 0xFF;
        }
    }
}
