package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** F-122 / SF-122-01 — le runner lance, surveille, relance et arrête un Chrome dédié, tout seul. */
class ManagedChromeTest {

    // --- Ligne de commande -------------------------------------------------------------------

    @Test
    @DisplayName("La ligne de commande porte le port, le profil dédié, la boucle locale, hors champ, "
            + "Teams — et jamais headless")
    void command_line_is_correct() {
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")),
                Path.of("/tmp/profil dédié"), 9333, new FakeSession(), FakeProbe.always(false),
                new FakeSleeper(), null);

        List<String> cmd = chrome.commandLine();

        assertEquals("/opt/chrome", cmd.get(0));
        assertTrue(cmd.contains("--remote-debugging-port=9333"), cmd.toString());
        assertTrue(cmd.contains("--remote-debugging-address=127.0.0.1"), cmd.toString());
        assertTrue(cmd.contains("--user-data-dir=/tmp/profil dédié"), cmd.toString());
        assertTrue(cmd.contains(ManagedChrome.OFFSCREEN), cmd.toString());
        assertEquals("https://teams.microsoft.com", cmd.get(cmd.size() - 1));
        assertFalse(cmd.stream().anyMatch(arg -> arg.contains("headless")), cmd.toString());
    }

    @Test
    @DisplayName("La ligne de commande auto-accepte la capture d'onglet, de façon ciblée (SF-122-05)")
    void command_line_auto_accepts_tab_capture() {
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")),
                Path.of("/tmp/profil"), 9333, new FakeSession(), FakeProbe.always(false),
                new FakeSleeper(), null);

        List<String> cmd = chrome.commandLine();

        assertTrue(cmd.contains(ManagedChrome.AUTO_ACCEPT_TAB_CAPTURE), cmd.toString());
        assertTrue(cmd.contains(ManagedChrome.AUTO_SELECT_TAB_BY_TITLE), cmd.toString());
        // Toujours l'URL Teams en dernier, jamais un auto-accept média large (décision PO).
        assertEquals("https://teams.microsoft.com", cmd.get(cmd.size() - 1));
        assertFalse(cmd.stream().anyMatch(arg -> arg.contains("use-fake-ui-for-media-stream")),
                cmd.toString());
        assertFalse(cmd.stream().anyMatch(arg -> arg.contains("auto-accept-camera-and-microphone")),
                cmd.toString());
    }

    // --- Cycle de vie ------------------------------------------------------------------------

    @Test
    @DisplayName("Le port répond déjà : REACHABLE, aucun lancement")
    void already_reachable_does_not_launch() {
        FakeSession session = new FakeSession();
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")),
                Path.of("/tmp/p"), 9222, session, FakeProbe.always(true), new FakeSleeper(), null);

        assertEquals(ManagedChrome.State.REACHABLE, chrome.ensureRunning());
        assertTrue(session.calls.isEmpty());
    }

    @Test
    @DisplayName("Le port répond après lancement : LAUNCHED, un seul lancement")
    void launches_then_reachable(@TempDir Path profile) {
        FakeSession session = new FakeSession();
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")), profile, 9222,
                session, FakeProbe.of(false, true), new FakeSleeper(), null);

        assertEquals(ManagedChrome.State.LAUNCHED, chrome.ensureRunning());
        assertEquals(1, session.calls.size());
        assertEquals("/opt/chrome", session.calls.get(0).get(0));
    }

    @Test
    @DisplayName("Le port ne répond jamais : UNREACHABLE, sans boucle infinie, un seul lancement")
    void never_reachable_gives_up(@TempDir Path profile) {
        FakeSession session = new FakeSession();
        FakeSleeper sleeper = new FakeSleeper();
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")),
                profile, 9222, session, FakeProbe.always(false), sleeper, null);

        assertEquals(ManagedChrome.State.UNREACHABLE, chrome.ensureRunning());
        assertEquals(1, session.calls.size());
        assertEquals((int) (ManagedChrome.PORT_WAIT_MS / ManagedChrome.POLL_STEP_MS), sleeper.count);
    }

    @Test
    @DisplayName("Aucun exécutable : NO_BROWSER, aucun lancement")
    void no_browser_found() {
        FakeSession session = new FakeSession();
        ManagedChrome chrome = new ManagedChrome(Optional.empty(), Path.of("/tmp/p"), 9222, session,
                FakeProbe.always(false), new FakeSleeper(), null);

        assertEquals(ManagedChrome.State.NO_BROWSER, chrome.ensureRunning());
        assertTrue(session.calls.isEmpty());
    }

    @Test
    @DisplayName("relaunchIfDead ne relance pas quand le port répond")
    void relaunch_no_op_when_reachable() {
        FakeSession session = new FakeSession();
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")),
                Path.of("/tmp/p"), 9222, session, FakeProbe.always(true), new FakeSleeper(), null);

        assertEquals(ManagedChrome.State.REACHABLE, chrome.relaunchIfDead());
        assertTrue(session.calls.isEmpty());
    }

    @Test
    @DisplayName("relaunchIfDead relance quand le port ne répond plus")
    void relaunch_when_dead(@TempDir Path profile) {
        FakeSession session = new FakeSession();
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")), profile, 9222,
                session, FakeProbe.of(false, true), new FakeSleeper(), null);

        assertEquals(ManagedChrome.State.LAUNCHED, chrome.relaunchIfDead());
        assertEquals(1, session.calls.size());
    }

    @Test
    @DisplayName("reveal fait surgir le Chrome managé à l'écran (sans le flag hors champ) — SF-122-06")
    void reveal_launches_on_screen(@TempDir Path profile) {
        FakeSession session = new FakeSession();
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")), profile, 9222,
                session, FakeProbe.of(false, true), new FakeSleeper(), null);

        assertEquals(ManagedChrome.State.LAUNCHED, chrome.reveal());
        assertEquals(1, session.calls.size());
        assertFalse(session.calls.get(0).contains(ManagedChrome.OFFSCREEN),
                session.calls.get(0).toString());
        assertEquals("https://teams.microsoft.com",
                session.calls.get(0).get(session.calls.get(0).size() - 1));
    }

    @Test
    @DisplayName("remask relance le Chrome managé hors champ — SF-122-06")
    void remask_launches_offscreen(@TempDir Path profile) {
        FakeSession session = new FakeSession();
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")), profile, 9222,
                session, FakeProbe.of(false, true), new FakeSleeper(), null);

        assertEquals(ManagedChrome.State.LAUNCHED, chrome.remask());
        assertEquals(1, session.calls.size());
        assertTrue(session.calls.get(0).contains(ManagedChrome.OFFSCREEN),
                session.calls.get(0).toString());
    }

    @Test
    @DisplayName("reveal relance : la fenêtre déjà ouverte est arrêtée avant d'être rouverte visible")
    void reveal_relaunches_over_existing(@TempDir Path profile) {
        FakeSession session = new FakeSession();
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")), profile, 9222,
                session, FakeProbe.of(false, true), new FakeSleeper(), null);
        chrome.ensureRunning();
        FakeHandle offscreen = session.last;

        chrome.reveal();

        assertTrue(offscreen.destroyed, "la fenêtre hors champ doit être arrêtée avant de surgir");
        assertEquals(2, session.calls.size());
        assertFalse(session.calls.get(1).contains(ManagedChrome.OFFSCREEN),
                session.calls.get(1).toString());
    }

    @Test
    @DisplayName("reveal sans navigateur : NO_BROWSER, aucun lancement — SF-122-06")
    void reveal_no_browser() {
        FakeSession session = new FakeSession();
        ManagedChrome chrome = new ManagedChrome(Optional.empty(), Path.of("/tmp/p"), 9222, session,
                FakeProbe.always(false), new FakeSleeper(), null);

        assertEquals(ManagedChrome.State.NO_BROWSER, chrome.reveal());
        assertTrue(session.calls.isEmpty());
    }

    // --- SF-122-08 : reprise robuste après fermeture manuelle/sale de Chrome ------------------

    @Test
    @DisplayName("recover : le port répond déjà ⇒ REACHABLE, aucun lancement, aucun nettoyage de verrou")
    void recover_reachable_does_nothing(@TempDir Path profile) throws Exception {
        Path lock = profile.resolve("SingletonLock");
        java.nio.file.Files.writeString(lock, "vivant");
        FakeSession session = new FakeSession();
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")), profile, 9222,
                session, FakeProbe.always(true), new FakeSleeper(), null);

        assertEquals(ManagedChrome.State.REACHABLE, chrome.recover());
        assertTrue(session.calls.isEmpty(), "un Chrome vivant n'est jamais relancé");
        assertTrue(java.nio.file.Files.exists(lock),
                "on ne touche jamais au verrou d'une instance vivante");
    }

    @Test
    @DisplayName("recover : port muet + fermeture sale ⇒ verrou nettoyé, relance, LAUNCHED")
    void recover_clears_lock_and_relaunches(@TempDir Path profile) throws Exception {
        Path lock = profile.resolve("SingletonLock");
        java.nio.file.Files.writeString(lock, "résiduel");
        FakeSession session = new FakeSession();
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")), profile, 9222,
                session, FakeProbe.of(false, true), new FakeSleeper(), null);

        assertEquals(ManagedChrome.State.LAUNCHED, chrome.recover());
        assertEquals(1, session.calls.size(), "une relance");
        assertFalse(java.nio.file.Files.exists(lock),
                "le verrou de profil résiduel doit être nettoyé avant la relance");
    }

    @Test
    @DisplayName("recover : le port remonte après plusieurs sondes ⇒ attend puis LAUNCHED (pas de conclusion hâtive)")
    void recover_waits_for_slow_port(@TempDir Path profile) {
        FakeSession session = new FakeSession();
        FakeSleeper sleeper = new FakeSleeper();
        // Port muet aux 3 premières sondes (dont celle de recover), puis répond : le backoff doit tenir.
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")), profile, 9222,
                session, FakeProbe.of(false, false, false, true), sleeper, null);

        assertEquals(ManagedChrome.State.LAUNCHED, chrome.recover());
        assertEquals(1, session.calls.size());
        assertTrue(sleeper.count >= 1, "on attend le port avant de conclure");
    }

    @Test
    @DisplayName("recover : le port ne répond jamais ⇒ UNREACHABLE après TOUT le backoff (échec réel)")
    void recover_unreachable_only_after_full_backoff(@TempDir Path profile) {
        FakeSession session = new FakeSession();
        FakeSleeper sleeper = new FakeSleeper();
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")), profile, 9222,
                session, FakeProbe.always(false), sleeper, null);

        assertEquals(ManagedChrome.State.UNREACHABLE, chrome.recover());
        assertEquals(1, session.calls.size());
        assertEquals((int) (ManagedChrome.PORT_WAIT_MS / ManagedChrome.POLL_STEP_MS), sleeper.count,
                "on attend tout le backoff avant de conclure injoignable");
    }

    @Test
    @DisplayName("recover : aucun exécutable ⇒ NO_BROWSER, aucun lancement")
    void recover_no_browser() {
        FakeSession session = new FakeSession();
        ManagedChrome chrome = new ManagedChrome(Optional.empty(), Path.of("/tmp/p"), 9222, session,
                FakeProbe.always(false), new FakeSleeper(), null);

        assertEquals(ManagedChrome.State.NO_BROWSER, chrome.recover());
        assertTrue(session.calls.isEmpty());
    }

    @Test
    @DisplayName("recover : oublie un handle mort avant de relancer (une seule relance)")
    void recover_forgets_dead_handle(@TempDir Path profile) {
        // 1er lancement (ensureRunning) puis le process meurt ; recover doit relancer une fois de plus.
        FakeSession session = new FakeSession();
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")), profile, 9222,
                session, FakeProbe.of(false, true, false, true), new FakeSleeper(), null);
        chrome.ensureRunning();
        session.last.alive = false; // le Chrome a été fermé manuellement

        assertEquals(ManagedChrome.State.LAUNCHED, chrome.recover());
        assertEquals(2, session.calls.size(), "recover relance une fois après la mort du handle");
    }

    @Test
    @DisplayName("stop arrête le processus lancé")
    void stop_destroys_process(@TempDir Path profile) {
        FakeSession session = new FakeSession();
        ManagedChrome chrome = new ManagedChrome(Optional.of(Path.of("/opt/chrome")), profile, 9222,
                session, FakeProbe.of(false, true), new FakeSleeper(), null);
        chrome.ensureRunning();

        chrome.stop();

        assertTrue(session.last.destroyed);
    }

    // --- Doublures ---------------------------------------------------------------------------

    /** Une session de processus de papier : capture ce qui est lancé, ne lance rien. */
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

    /** Une sonde de papier : rend une suite de réponses, puis répète la dernière. */
    static final class FakeProbe implements ManagedChrome.Probe {
        private final Deque<Boolean> answers;
        private final boolean tail;
        int calls;

        private FakeProbe(boolean tail, Boolean... sequence) {
            this.answers = new ArrayDeque<>(List.of(sequence));
            this.tail = tail;
        }

        static FakeProbe always(boolean value) {
            return new FakeProbe(value);
        }

        /** Rend {@code sequence} dans l'ordre, puis {@code true} ensuite. */
        static FakeProbe of(Boolean... sequence) {
            return new FakeProbe(true, sequence);
        }

        @Override
        public boolean reachable(int port) {
            calls++;
            return answers.isEmpty() ? tail : answers.poll();
        }
    }

    static final class FakeSleeper implements BrowserLink.Sleeper {
        int count;

        @Override
        public void sleep(long millis) {
            count++;
        }
    }
}
