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
    void laRetentionGardeLaCouranteEtDeuxPrecedentes() throws Exception {
        LauncherHome home = new LauncherHome(dir);
        String[] ids = { "1.0.0-202601010000-a", "1.0.0-202602010000-b", "1.0.0-202603010000-c",
                "1.0.0-202604010000-d", "1.1.0-202605010000-e" };
        for (String id : ids) {
            home.install(id, id.getBytes(StandardCharsets.UTF_8));
        }

        java.util.List<String> removed = home.prune("1.0.0-202604010000-d", 2);

        assertEquals(java.util.Set.of("1.0.0-202601010000-a", "1.1.0-202605010000-e"), java.util.Set.copyOf(removed),
                "la plus ancienne, et une plus récente que la courante (échec d'essai), sont supprimées");
        assertTrue(home.isInstalled("1.0.0-202604010000-d"));
        assertTrue(home.isInstalled("1.0.0-202603010000-c"));
        assertTrue(home.isInstalled("1.0.0-202602010000-b"));
        assertFalse(Files.exists(dir.resolve("versions").resolve("1.0.0-202601010000-a")));
    }

    @Test
    void leTemoinDeSanteNommeLaVersionConnectee() throws Exception {
        LauncherHome home = new LauncherHome(dir);
        assertTrue(home.connectedVersion().isEmpty());

        home.markConnected(V1, 1234);
        assertEquals(V1, home.connectedVersion().orElseThrow());

        home.clearHealth();
        assertTrue(home.connectedVersion().isEmpty());
    }

    @Test
    void leRapportDeRetourEstUnJsonLisible() throws Exception {
        LauncherHome home = new LauncherHome(dir);
        home.writeReport(new LauncherHome.UpdateReport(V1, "1.1.0-202605010000-e", "rolled_back",
                "la version « 1.1.0 » ne s'est pas reconnectée\nen 90 s"));

        com.fasterxml.jackson.databind.JsonNode report =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(home.reportFile().toFile());
        assertEquals(V1, report.path("from").asText());
        assertEquals("rolled_back", report.path("result").asText());
        assertTrue(report.path("reason").asText().contains("« 1.1.0 »"));

        home.clearReport();
        assertFalse(Files.exists(home.reportFile()));
    }

    @Test
    void leDossierSuitLaVariableOuLeDossierPersonnel() {
        assertEquals(Path.of("/tmp/ailleurs"),
                LauncherHome.resolve(Map.of(LauncherHome.HOME_ENV, "/tmp/ailleurs"), "/home/u").root());
        assertEquals(Path.of("/home/u", ".claude-runner"), LauncherHome.resolve(Map.of(), "/home/u").root());
    }
}
