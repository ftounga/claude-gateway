package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fr.claudegateway.runner.OperatingSystem;
import fr.claudegateway.runner.diag.RunnerDiag;
import fr.claudegateway.runner.diag.RunnerDiagEvent;
import fr.claudegateway.runner.diag.RunnerDiagLevel;

/**
 * F-122 / SF-122-06 — la boucle Vigie assemble et remonte l'état, best-effort, sans jamais casser le
 * runner ; le Chrome managé est idempotent et arrêté proprement.
 */
class VigieLoopTest {

    @BeforeEach
    @AfterEach
    void cleanDiag() {
        RunnerDiag.reset();
    }

    // --- Diagnostic F-132 / SF-132-01 -------------------------------------------------------

    @Test
    @DisplayName("Diag F-132 : un relevé émet l'état Chrome et l'état de session (INFO), pas le tick (DEBUG)")
    void tick_emits_chrome_and_session_events() {
        VigieLoop loop = new VigieLoop(reachableChrome(), connectedSonde(), new RecordingUploader(),
                OperatingSystem.LINUX, msg -> { });

        loop.tick();

        RunnerDiag.Drained drained = RunnerDiag.drain(100);
        assertTrue(drained.events().stream()
                .anyMatch(e -> e.cat().equals("chrome") && e.code().equals("chrome_state")));
        assertTrue(drained.events().stream()
                .anyMatch(e -> e.cat().equals("teams") && e.code().equals("session_state")));
        assertFalse(drained.events().stream().anyMatch(e -> e.code().equals("tick")),
                "le tick est en DEBUG, filtré au seuil INFO par défaut");
    }

    @Test
    @DisplayName("Diag F-132 : l'état Chrome porte le NOM de l'exécutable et le port, jamais le chemin complet")
    void chrome_event_carries_exe_name_not_path() {
        VigieLoop loop = new VigieLoop(reachableChrome(), connectedSonde(), new RecordingUploader(),
                OperatingSystem.LINUX, msg -> { });

        loop.tick();

        RunnerDiagEvent chrome = RunnerDiag.drain(100).events().stream()
                .filter(e -> e.code().equals("chrome_state")).findFirst().orElseThrow();
        assertEquals("REACHABLE", chrome.fields().get("state"));
        assertEquals(9222, chrome.fields().get("port"));
        assertEquals("chrome", chrome.fields().get("exe"), "le NOM du fichier, jamais /opt/chrome");
    }

    @Test
    @DisplayName("Diag F-132 : l'état Chrome n'est réémis qu'à la bascule (anti-spam)")
    void chrome_event_only_on_transition() {
        VigieLoop loop = new VigieLoop(reachableChrome(), connectedSonde(), new RecordingUploader(),
                OperatingSystem.LINUX, msg -> { });

        loop.tick();
        RunnerDiag.drain(100);
        loop.tick(); // même état : ne doit pas réémettre chrome_state

        assertFalse(RunnerDiag.drain(100).events().stream()
                .anyMatch(e -> e.code().equals("chrome_state")), "pas de spam à état constant");
    }

    @Test
    @DisplayName("Diag F-132 : en DEBUG (SF-132-05), le tick de la boucle est émis")
    void tick_emitted_at_debug_level() {
        RunnerDiag.setLevel(RunnerDiagLevel.DEBUG);
        VigieLoop loop = new VigieLoop(reachableChrome(), connectedSonde(), new RecordingUploader(),
                OperatingSystem.LINUX, msg -> { });

        loop.tick();

        assertTrue(RunnerDiag.drain(100).events().stream()
                .anyMatch(e -> e.cat().equals("vigie") && e.code().equals("tick")));
    }

    // --- Assemble + remonte -----------------------------------------------------------------

    @Test
    @DisplayName("Chrome joignable + sonde OK : le rapport est assemblé et remonté (tout au vert)")
    void assembles_and_uploads_when_reachable() {
        RecordingUploader uploader = new RecordingUploader();
        VigieLoop loop = new VigieLoop(reachableChrome(), connectedSonde(), uploader,
                OperatingSystem.LINUX, msg -> { });

        VigieReadinessReport report = loop.tick();

        assertNotNull(report);
        assertTrue(report.chromeReachable());
        assertTrue(report.teamsConnected());
        assertTrue(report.teamsReadTest());
        assertEquals(1, uploader.reports.size());
        assertTrue(uploader.reports.get(0).chromeReachable());
    }

    @Test
    @DisplayName("Aucun navigateur : NO_BROWSER remonté, la sonde n'est pas appelée, un diagnostic est dit")
    void reports_no_browser_when_absent() {
        RecordingUploader uploader = new RecordingUploader();
        CountingSonde sonde = new CountingSonde(
                new VigieLoop.Reading(TeamsSessionState.CONNECTED, true));
        List<String> said = new ArrayList<>();
        VigieLoop loop = new VigieLoop(noBrowserChrome(), sonde, uploader,
                OperatingSystem.LINUX, said::add);

        VigieReadinessReport report = loop.tick();

        assertFalse(report.chromeReachable());
        assertEquals(0, sonde.calls, "la sonde ne doit pas tourner sans Chrome joignable");
        assertEquals(1, uploader.reports.size());
        assertTrue(said.stream().anyMatch(s -> s.contains("Chrome introuvable")), said.toString());
    }

    @Test
    @DisplayName("Port muet après lancement : UNREACHABLE remonté, diagnostic policy d'entreprise")
    void reports_unreachable_when_port_silent(@TempDir Path profile) {
        RecordingUploader uploader = new RecordingUploader();
        List<String> said = new ArrayList<>();
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")), profile, 9222,
                new FakeSession(), port -> false, millis -> { }, said::add);
        VigieLoop loop = new VigieLoop(chrome, connectedSonde(), uploader,
                OperatingSystem.LINUX, said::add);

        VigieReadinessReport report = loop.tick();

        assertFalse(report.chromeReachable());
        assertEquals(1, uploader.reports.size());
        assertTrue(said.stream().anyMatch(s -> s.contains("Débogage Chrome bloqué")), said.toString());
    }

    // --- Idempotence ------------------------------------------------------------------------

    @Test
    @DisplayName("Chrome déjà joignable : aucun relancement, même après plusieurs relevés")
    void idempotent_no_relaunch_when_reachable() {
        FakeSession session = new FakeSession();
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")),
                Path.of("/tmp/p"), 9222, session, port -> true, millis -> { }, msg -> { });
        VigieLoop loop = new VigieLoop(chrome, connectedSonde(), new RecordingUploader(),
                OperatingSystem.LINUX, msg -> { });

        loop.tick();
        loop.tick();
        loop.tick();

        assertTrue(session.calls.isEmpty(), "un Chrome joignable ne doit jamais être relancé");
    }

    // --- Best-effort, jamais fatal ----------------------------------------------------------

    @Test
    @DisplayName("Sonde en échec : non fatal, l'état est quand même remonté (pas encore observé)")
    void sonde_failure_is_not_fatal() {
        RecordingUploader uploader = new RecordingUploader();
        VigieLoop.Sonde exploding = () -> {
            throw new IllegalStateException("liaison perdue");
        };
        VigieLoop loop = new VigieLoop(reachableChrome(), exploding, uploader,
                OperatingSystem.LINUX, msg -> { });

        VigieReadinessReport report = loop.tick();

        assertTrue(report.chromeReachable());
        assertFalse(report.teamsConnected());
        assertEquals(1, uploader.reports.size());
    }

    @Test
    @DisplayName("Remontée en échec : non fatal, et l'échec n'est dit qu'une fois (anti-spam)")
    void upload_failure_is_not_fatal_and_not_spammed() {
        List<String> said = new ArrayList<>();
        VigieReadinessUploader failing = report -> {
            throw new IOException("gateway indisponible");
        };
        VigieLoop loop = new VigieLoop(reachableChrome(), connectedSonde(), failing,
                OperatingSystem.LINUX, said::add);

        loop.safeTick();
        loop.safeTick();

        long failureLines = said.stream().filter(s -> s.contains("n'a pas pu remonter")).count();
        assertEquals(1, failureLines, "l'échec de remontée ne doit être dit qu'à la bascule");
    }

    @Test
    @DisplayName("safeTick avale toute exception : le heartbeat partagé n'est jamais tué")
    void safeTick_swallows_everything() {
        ManagedChrome throwingChrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")),
                Path.of("/tmp/p"), 9222, new FakeSession(), port -> {
                    throw new RuntimeException("sonde du port cassée");
                }, millis -> { }, msg -> { });
        VigieLoop loop = new VigieLoop(throwingChrome, connectedSonde(), new RecordingUploader(),
                OperatingSystem.LINUX, msg -> { });

        loop.safeTick(); // ne doit rien laisser remonter
    }

    // --- Démarrage / arrêt propres ----------------------------------------------------------

    @Test
    @DisplayName("start planifie des relevés ; stop les arrête et arrête le Chrome managé (pas d'orphelin)")
    void start_then_stop_stops_chrome_and_halts_ticks() throws InterruptedException {
        FakeSession session = new FakeSession();
        // Chrome qui se lance puis répond : un handle existe, donc stop() doit le détruire.
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")),
                Path.of("/tmp/p"), 9222, session, sequencedProbe(false, true), millis -> { },
                msg -> { });
        CountDownLatch firstTick = new CountDownLatch(1);
        RecordingUploader uploader = new RecordingUploader(firstTick);
        // Période énorme : seul le relevé immédiat (délai initial 0) tombe dans la fenêtre du test.
        VigieLoop loop = new VigieLoop(chrome, connectedSonde(), uploader,
                OperatingSystem.LINUX, msg -> { }, 3_600L);
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            loop.start(executor);
            assertTrue(firstTick.await(5, TimeUnit.SECONDS), "un premier relevé doit avoir eu lieu");

            loop.stop();

            assertNotNull(session.last);
            assertTrue(session.last.destroyed, "le Chrome managé doit être arrêté (pas d'orphelin)");
            int after = uploader.reports.size();
            Thread.sleep(50);
            assertEquals(after, uploader.reports.size(), "la boucle doit être stoppée après stop()");
            loop.stop(); // idempotent
        } finally {
            executor.shutdownNow();
        }
    }

    // --- Doublures --------------------------------------------------------------------------

    private static ManagedChrome reachableChrome() {
        return new ManagedChrome(Optional.of(Path.of("/opt/chrome")), Path.of("/tmp/p"), 9222,
                new FakeSession(), port -> true, millis -> { }, msg -> { });
    }

    private static ManagedChrome noBrowserChrome() {
        return new ManagedChrome(Optional.empty(), Path.of("/tmp/p"), 9222, new FakeSession(),
                port -> false, millis -> { }, msg -> { });
    }

    private static VigieLoop.Sonde connectedSonde() {
        return () -> new VigieLoop.Reading(TeamsSessionState.CONNECTED, true);
    }

    private static ManagedChrome.Probe sequencedProbe(Boolean... sequence) {
        Deque<Boolean> answers = new ArrayDeque<>(List.of(sequence));
        return port -> answers.isEmpty() ? Boolean.TRUE : answers.poll();
    }

    static final class CountingSonde implements VigieLoop.Sonde {
        private final VigieLoop.Reading reading;
        int calls;

        CountingSonde(VigieLoop.Reading reading) {
            this.reading = reading;
        }

        @Override
        public VigieLoop.Reading sense() {
            calls++;
            return reading;
        }
    }

    static final class RecordingUploader implements VigieReadinessUploader {
        final List<VigieReadinessReport> reports = new ArrayList<>();
        private final CountDownLatch latch;

        RecordingUploader() {
            this(null);
        }

        RecordingUploader(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public synchronized void upload(VigieReadinessReport report) {
            reports.add(report);
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    static final class FakeSession implements ProcessSession {
        final List<List<String>> calls = new ArrayList<>();
        FakeHandle last;

        @Override
        public Handle start(List<String> command, Path workingDir) {
            calls.add(List.copyOf(command));
            last = new FakeHandle();
            return last;
        }
    }

    static final class FakeHandle implements ProcessSession.Handle {
        boolean alive = true;
        boolean destroyed;

        @Override
        public boolean alive() {
            return alive;
        }

        @Override
        public void requestStop() {
        }

        @Override
        public int awaitExit(long timeoutMs) {
            return alive ? -1 : 0;
        }

        @Override
        public void destroy() {
            destroyed = true;
            alive = false;
        }

        @Override
        public List<String> tail() {
            return List.of();
        }
    }
}
