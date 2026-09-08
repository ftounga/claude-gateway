package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Structure du paquet autonome Windows (F-44 / SF-44-01).
 *
 * <p>Le paquet demande le téléchargement d'un JDK de 200 Mo : on ne l'impose pas à chaque exécution
 * de la suite. Ces tests s'exécutent <b>quand le paquet est là</b> — donc dans le build d'image, où
 * il est construit — et sont ignorés sinon. Un test ignoré le dit ; un test absent ne dit rien.</p>
 *
 * <p>Pour les exécuter localement : {@code ./package-windows.sh target/claude-runner.jar target}</p>
 */
class WindowsPackageTest {

    private static final Path PACKAGE =
            Path.of("target", "claude-runner-windows-x64.zip");

    static boolean packageWasBuilt() {
        return Files.exists(PACKAGE);
    }

    @Test
    @EnabledIf("packageWasBuilt")
    @DisplayName("le paquet contient l'application, son lanceur et sa propre JVM")
    void thePackageCarriesItsOwnJvm() throws IOException {
        try (ZipFile zip = new ZipFile(PACKAGE.toFile())) {
            assertNotNull(zip.getEntry("claude-runner/claude-runner.jar"),
                    "le runner lui-même est absent du paquet");
            assertNotNull(zip.getEntry("claude-runner/claude-runner.cmd"),
                    "le lanceur est absent : rien ne serait exécutable");
            // Le cœur de la feature : sans java.exe, le paquet ne vaut pas mieux que le jar seul.
            assertNotNull(zip.getEntry("claude-runner/runtime/bin/java.exe"),
                    "la JVM embarquée est absente — le prérequis Java n'est pas supprimé");
        }
    }

    @Test
    @EnabledIf("packageWasBuilt")
    @DisplayName("le lanceur appelle la JVM du paquet, jamais celle du système")
    void theLauncherUsesTheBundledJvm() throws IOException {
        String cmd = entryAsText("claude-runner/claude-runner.cmd");

        assertTrue(cmd.contains("runtime\\bin\\java.exe"),
                "le lanceur doit désigner la JVM du paquet : " + cmd);
        // Sans %*, les arguments --gateway / --workspace / --code seraient perdus.
        assertTrue(cmd.contains("%*"), "le lanceur doit transmettre les arguments : " + cmd);
        // CRLF : un .cmd en LF est refusé par certains shells Windows. C'est le genre de détail qui
        // transforme un paquet correct en « ça ne marche pas » chez le client.
        assertTrue(cmd.contains("\r\n"), "le lanceur doit être en CRLF");
    }

    @Test
    @EnabledIf("packageWasBuilt")
    @DisplayName("double-cliqué, le lanceur ne se referme pas sur un refus (F-46 / SF-46-02)")
    void theLauncherKeepsItsWindowOpenAfterADoubleClick() throws IOException {
        String cmd = entryAsText("claude-runner/claude-runner.cmd");

        // Le lanceur existe pour être double-cliqué, et un double-clic ne transmet aucun argument :
        // depuis SF-46-01 la configuration mémorisée prend le relais. Reste le cas où le runner
        // refuse (jamais appairé, jeton expiré) — sans cette garde, la fenêtre se refermerait sur
        // le message, et le refus le plus soigné du monde ne serait jamais lu.
        assertTrue(cmd.contains("%cmdcmdline%"),
                "le lanceur doit savoir s'il a été double-cliqué : " + cmd);
        assertTrue(cmd.contains("pause"),
                "le lanceur doit retenir sa fenêtre après un échec au double-clic : " + cmd);
        // Et seulement là : une pause inconditionnelle bloquerait tout appel depuis un terminal.
        assertTrue(cmd.contains("if errorlevel 1"),
                "la pause doit être conditionnée à l'échec : " + cmd);
    }

    @Test
    @EnabledIf("packageWasBuilt")
    @DisplayName("le paquet reste sous 60 Mo")
    void thePackageStaysDownloadable() throws IOException {
        long megabytes = Files.size(PACKAGE) / (1024 * 1024);
        assertTrue(megabytes < 60,
                "le paquet pèse " + megabytes + " Mo : au-delà de 60, le premier contact devient "
                        + "un obstacle en soi");
    }

    @Test
    @EnabledIf("packageWasBuilt")
    @DisplayName("le jar du paquet est celui qui est servi par ailleurs")
    void thePackagedJarIsTheOneWeShip() throws IOException {
        try (ZipFile zip = new ZipFile(PACKAGE.toFile())) {
            ZipEntry packaged = zip.getEntry("claude-runner/claude-runner.jar");
            // D4 : F-44 ajoute un format, elle n'en remplace aucun. Deux versions différentes du
            // runner selon le format téléchargé seraient un piège de diagnostic.
            assertEquals(Files.size(Path.of("target", "claude-runner.jar")), packaged.getSize(),
                    "le jar du paquet diffère de celui construit par le module");
        }
    }

    private String entryAsText(String name) throws IOException {
        try (ZipFile zip = new ZipFile(PACKAGE.toFile());
                InputStream in = zip.getInputStream(zip.getEntry(name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
