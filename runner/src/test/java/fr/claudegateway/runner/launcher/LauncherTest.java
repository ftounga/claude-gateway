package fr.claudegateway.runner.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fr.claudegateway.runner.RunnerBuild;

/** F-111 / SF-111-02 — les décisions du lanceur, sans processus. */
class LauncherTest {

    private static final String OLD = "1.0.0-202609130000-aaaaaaa";
    private static final String NEW = "1.0.0-202609140000-bbbbbbb";

    @TempDir
    Path dir;

    @Test
    void lesModesPonctuelsEtLeDiagnosticTournentSansLanceur() {
        assertTrue(Launcher.runsDirectly(new String[] { "--no-launcher" }, Map.of()));
        assertTrue(Launcher.runsDirectly(new String[] { "--gateway", "https://x", "--check" }, Map.of()));
        assertTrue(Launcher.runsDirectly(new String[] {}, Map.of("CLAUDE_RUNNER_CHECK", "true")));
        assertTrue(Launcher.runsDirectly(new String[] { "--releve-teams" }, Map.of()));
        assertTrue(Launcher.runsDirectly(new String[] { "--releve-teams=5" }, Map.of()));
        assertTrue(Launcher.runsDirectly(new String[] {}, Map.of(JavaChild.LAUNCHER_PID_ENV, "42")),
                "un enfant ne se relance jamais lui-même");
        assertFalse(Launcher.runsDirectly(new String[] { "--gateway", "https://x", "--root", "/tmp" },
                Map.of()));
        assertFalse(Launcher.runsDirectly(null, null));
    }

    @Test
    void leDrapeauDuLanceurNEstPasTransmisAuRunner() {
        assertArrayEquals(new String[] { "--root", "/tmp" },
                Launcher.withoutLauncherFlag(new String[] { "--no-launcher", "--root", "/tmp" }));
    }

    @Test
    void premierLancementInstalleLeJarLanceEtLeDemarre() throws Exception {
        LauncherHome home = new LauncherHome(dir.resolve("home"));
        Launcher launcher = launcher(home, OLD);

        assertEquals(OLD, launcher.startVersion());
        assertTrue(home.isInstalled(OLD));
        assertEquals(OLD, home.currentVersion().orElseThrow());
    }

    @Test
    void uneVersionPlusRecenteDejaInstalleeEstDemarree() throws Exception {
        LauncherHome home = new LauncherHome(dir.resolve("home"));
        home.install(NEW, "nouveau".getBytes(StandardCharsets.UTF_8));
        home.setCurrentVersion(NEW);

        assertEquals(NEW, launcher(home, OLD).startVersion(),
                "relancer à la main l'ancien jar ne fait pas revenir en arrière");
    }

    @Test
    void jamaisUneVersionPlusAncienneQueLeJarLance() throws Exception {
        LauncherHome home = new LauncherHome(dir.resolve("home"));
        home.install(OLD, "ancien".getBytes(StandardCharsets.UTF_8));
        home.setCurrentVersion(OLD);

        assertEquals(NEW, launcher(home, NEW).startVersion(),
                "un jar plus récent téléchargé à la main l'emporte");
        assertEquals(NEW, home.currentVersion().orElseThrow());
    }

    @Test
    void uneVersionCouranteAltereeNEstPasDemarree() throws Exception {
        LauncherHome home = new LauncherHome(dir.resolve("home"));
        Path jar = home.install(NEW, "nouveau".getBytes(StandardCharsets.UTF_8));
        home.setCurrentVersion(NEW);
        Files.writeString(jar, "altéré");

        assertEquals(OLD, launcher(home, OLD).startVersion());
    }

    @Test
    void unDossierNonInscriptibleNEmpechePasDeDemarrer() throws Exception {
        Path file = Files.writeString(dir.resolve("pas-un-dossier"), "x");
        List<String> said = new ArrayList<>();
        Launcher launcher = new Launcher(new LauncherHome(file.resolve("home")), child(),
                RunnerBuild.parseId(OLD).orElseThrow(), ownJar(), said::add, Clock.systemUTC(), false);

        assertEquals(OLD, launcher.startVersion());
        assertTrue(said.stream().anyMatch(line -> line.contains("sans mise à jour automatique")), said.toString());
    }

    @Test
    void laComparaisonDesVersionsSuitLaConstruction() {
        assertTrue(Launcher.isNewer(NEW, OLD));
        assertFalse(Launcher.isNewer(OLD, NEW));
        assertFalse(Launcher.isNewer(OLD, OLD));
        assertFalse(Launcher.isNewer("../x", OLD));
    }

    private Launcher launcher(LauncherHome home, String embedded) throws Exception {
        return new Launcher(home, child(), RunnerBuild.parseId(embedded).orElseThrow(), ownJar(),
                line -> { }, Clock.systemUTC(), false);
    }

    private Path ownJar() throws Exception {
        Path jar = dir.resolve("claude-runner.jar");
        if (!Files.exists(jar)) {
            Files.writeString(jar, "le jar lancé");
        }
        return jar;
    }

    private static JavaChild child() {
        return new JavaChild(Path.of("java"), List.of(), JavaChild.RUNNER_MAIN, Map.of());
    }
}
