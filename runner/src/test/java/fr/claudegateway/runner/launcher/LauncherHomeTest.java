package fr.claudegateway.runner.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** F-111 / SF-111-02 — le dossier du lanceur. */
class LauncherHomeTest {

    private static final String V1 = "1.0.0-202609130000-aaaaaaa";

    @TempDir
    Path dir;

    @Test
    void installeUneVersionEtRelitSonEmpreinte() throws Exception {
        LauncherHome home = new LauncherHome(dir);
        Path source = Files.write(dir.resolve("claude-runner.jar"), "jar".getBytes(StandardCharsets.UTF_8));

        Path installed = home.install(V1, source);

        assertEquals(dir.resolve("versions").resolve(V1).resolve("runner.jar"), installed);
        assertTrue(home.isInstalled(V1));
        assertFalse(Files.exists(installed.resolveSibling("runner.jar.tmp")), "rien ne reste à côté");
    }

    @Test
    void unJarModifieSurLeDisqueNEstPlusInstalle() throws Exception {
        LauncherHome home = new LauncherHome(dir);
        Path installed = home.install(V1, "jar".getBytes(StandardCharsets.UTF_8));

        Files.writeString(installed, "jar modifié");

        assertFalse(home.isInstalled(V1));
    }

    @Test
    void unIdentifiantNEstJamaisUnChemin() {
        LauncherHome home = new LauncherHome(dir);
        assertThrows(IllegalArgumentException.class, () -> home.jarOf("../../etc"));
        assertFalse(home.isInstalled("../x"));
        assertThrows(IllegalArgumentException.class, () -> home.setNextVersion("1.0.0/../../x"));
    }

    @Test
    void laVersionSuivanteSEcritSeLitEtSeConsomme() throws Exception {
        LauncherHome home = new LauncherHome(dir);
        assertTrue(home.nextVersion().isEmpty());

        home.setNextVersion(V1);
        assertEquals(V1, home.nextVersion().orElseThrow());

        home.clearNextVersion();
        assertTrue(home.nextVersion().isEmpty());
    }

    @Test
    void unFichierDeVersionIllisibleNeDecideDeRien() throws Exception {
        Files.writeString(dir.resolve("current-version"), "n'importe quoi");
        assertTrue(new LauncherHome(dir).currentVersion().isEmpty());
    }

    @Test
    void leDossierSuitLaVariableOuLeDossierPersonnel() {
        assertEquals(Path.of("/tmp/ailleurs"),
                LauncherHome.resolve(Map.of(LauncherHome.HOME_ENV, "/tmp/ailleurs"), "/home/u").root());
        assertEquals(Path.of("/home/u", ".claude-runner"), LauncherHome.resolve(Map.of(), "/home/u").root());
    }
}
