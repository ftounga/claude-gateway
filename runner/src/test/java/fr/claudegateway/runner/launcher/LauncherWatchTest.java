package fr.claudegateway.runner.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** F-111 / SF-111-02 — le runner s'arrête si son lanceur disparaît. */
class LauncherWatchTest {

    @Test
    void unLanceurVivantEstGarde() {
        assertFalse(LauncherWatch.launcherGone(42, pid -> Optional.of(true)));
    }

    @Test
    void unLanceurMortOuIntrouvableArreteLeRunner() {
        assertTrue(LauncherWatch.launcherGone(42, pid -> Optional.of(false)));
        assertTrue(LauncherWatch.launcherGone(42, pid -> Optional.empty()));
    }

    @Test
    void laPresenceDuLanceurSeLitDansLEnvironnement() {
        assertTrue(LauncherWatch.underLauncher(Map.of(JavaChild.LAUNCHER_PID_ENV, "1234")));
        assertEquals(1234L, LauncherWatch.launcherPid(Map.of(JavaChild.LAUNCHER_PID_ENV, " 1234 ")).orElseThrow());
        assertFalse(LauncherWatch.underLauncher(Map.of()));
        assertFalse(LauncherWatch.underLauncher(Map.of(JavaChild.LAUNCHER_PID_ENV, "abc")));
        assertFalse(LauncherWatch.underLauncher(null));
    }

    @Test
    void leRapportDuLanceurEstRemisPuisEfface(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        Map<String, String> env = Map.of(JavaChild.LAUNCHER_PID_ENV, "1", LauncherHome.HOME_ENV, dir.toString());
        assertEquals(null, LauncherWatch.pendingReport(env));
        new LauncherHome(dir).writeReport(new LauncherHome.UpdateReport("1.0.0", "1.1.0", "rolled_back", "motif"));

        com.fasterxml.jackson.databind.JsonNode report = LauncherWatch.pendingReport(env);
        assertEquals("1.1.0", report.path("to").asText());
        LauncherWatch.clearReport(env);
        assertEquals(null, LauncherWatch.pendingReport(env));

        java.nio.file.Files.writeString(new LauncherHome(dir).reportFile(), "{ illisible");
        assertEquals(null, LauncherWatch.pendingReport(env), "un rapport illisible est effacé, jamais envoyé");
        assertFalse(java.nio.file.Files.exists(new LauncherHome(dir).reportFile()));
        assertEquals(null, LauncherWatch.pendingReport(Map.of()), "sans lanceur, aucun rapport");
    }

    @Test
    void leProcessusCourantEstVivant() {
        assertFalse(LauncherWatch.launcherGone(ProcessHandle.current().pid(),
                pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive)));
    }
}
