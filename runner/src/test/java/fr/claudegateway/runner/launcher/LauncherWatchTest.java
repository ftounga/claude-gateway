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
    void leProcessusCourantEstVivant() {
        assertFalse(LauncherWatch.launcherGone(ProcessHandle.current().pid(),
                pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive)));
    }
}
