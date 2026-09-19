package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.diag.RunnerDiag;

/**
 * Le ré-armement de la capture (F-128 / SF-128-12) — décision et câblage CDP, contre un faux.
 *
 * <p>Le succès de bout en bout (rechargement réel de la page de réunion, {@code getDisplayMedia}
 * après ré-injection) est « À VALIDER SUR CALL RÉEL ». Ici : arme le domaine Page, sonde l'activité,
 * ré-injecte quand la capture est perdue, ne double jamais un enregistrement vivant, se tait une fois
 * désarmé, et avale un Chrome injoignable.</p>
 */
class CaptureReinjectorTest {

    @BeforeEach
    @AfterEach
    void cleanDiag() {
        RunnerDiag.reset();
    }

    @Test
    @DisplayName("arm() active Page.enable et s'abonne aux événements de chargement")
    void armEnablesPageAndSubscribes() {
        FakePageConnection connection = new FakePageConnection();
        CaptureReinjector reinjector =
                new CaptureReinjector(connection, () -> (script, gesture) -> null);

        reinjector.arm();

        assertTrue(reinjector.armed());
        assertTrue(connection.sent().contains(CdpCommands.PAGE_ENABLE),
                "le domaine Page doit être activé pour recevoir les événements de chargement");
        assertTrue(connection.subscribed().contains(CaptureReinjector.LOAD_EVENT),
                "on doit écouter Page.loadEventFired");
        assertTrue(connection.subscribed().contains(CaptureReinjector.FRAME_NAVIGATED),
                "on doit écouter Page.frameNavigated");
    }

    @Test
    @DisplayName("navigation + capture perdue ⇒ re-résout la cible fraîche puis ré-injecte avec geste")
    void navigationWhenInactiveReinjectsWithUserGesture() {
        FakePageConnection connection = new FakePageConnection();
        RecordingEval eval = new RecordingEval(false); // la sonde dira : capture inactive
        CaptureReinjector reinjector = new CaptureReinjector(connection, () -> eval);
        reinjector.arm();

        connection.fireLoad();

        assertEquals(1, reinjector.reinjections(), "une navigation avec capture perdue ré-injecte");
        assertTrue(eval.reinjectedWithGesture(),
                "START_SCRIPT doit être ré-injecté avec userGesture (getDisplayMedia l'exige)");
        var events = RunnerDiag.drain(100).events();
        assertTrue(events.stream()
                        .anyMatch(e -> e.cat().equals("capture") && e.code().equals("reattach")
                                && "ok".equals(e.fields().get("result"))),
                "la re-résolution de la cible doit être diagnostiquée (F-132)");
        assertTrue(events.stream()
                        .anyMatch(e -> e.cat().equals("capture") && e.code().equals("reinject")
                                && "ok".equals(e.fields().get("result"))),
                "une ré-injection réussie doit être diagnostiquée (F-132)");
    }

    @Test
    @DisplayName("ancienne cible morte + nouvelle cible Teams présente ⇒ re-résout et ré-injecte")
    void reattachesToNewTargetWhenOldOneIsDead() {
        FakePageConnection connection = new FakePageConnection();
        // L'Eval de l'ancienne cible lèverait (socket mort) ; la re-résolution DOIT rendre la cible fraîche.
        CaptureReinjector.Eval deadTarget = (script, gesture) -> {
            throw new BrowserLinkException(BrowserLinkException.LINK_LOST, "socket fermé");
        };
        RecordingEval freshTarget = new RecordingEval(false);
        boolean[] resolved = { false };
        CaptureReinjector.Reattacher reattacher = () -> {
            resolved[0] = true;
            return freshTarget; // on ne rend JAMAIS deadTarget : on re-résout à neuf
        };
        CaptureReinjector reinjector = new CaptureReinjector(connection, reattacher);
        // deadTarget n'est utilisé nulle part : la preuve que le lien capturé au start n'est pas réutilisé.
        assertFalse(deadTarget == freshTarget);
        reinjector.arm();

        connection.fireLoad();

        assertTrue(resolved[0], "la ré-injection doit re-résoudre la cible à chaque navigation");
        assertEquals(1, reinjector.reinjections(),
                "avec une nouvelle cible Teams joignable, la ré-injection réussit");
        assertTrue(freshTarget.reinjectedWithGesture(),
                "START_SCRIPT est ré-injecté sur la cible FRAÎCHE, avec userGesture");
    }

    @Test
    @DisplayName("aucune cible Teams joignable ⇒ no_teams_tab, pas de ré-injection (seul cas injoignable)")
    void concludesUnreachableOnlyWhenNoTeamsTab() {
        FakePageConnection connection = new FakePageConnection();
        // La re-résolution ne trouve aucun onglet Teams : elle rend null.
        CaptureReinjector reinjector = new CaptureReinjector(connection, () -> null);
        reinjector.arm();

        connection.fireLoad();

        assertEquals(0, reinjector.reinjections(), "sans cible joignable, on ne ré-injecte pas");
        assertTrue(RunnerDiag.drain(100).events().stream()
                        .anyMatch(e -> e.cat().equals("capture") && e.code().equals("reattach")
                                && "no_teams_tab".equals(e.fields().get("reason"))),
                "l'absence totale de cible Teams doit être diagnostiquée en capture/reattach");
    }

    @Test
    @DisplayName("navigation mais capture encore active ⇒ pas de ré-injection (garde)")
    void navigationWhenActiveDoesNotReinject() {
        FakePageConnection connection = new FakePageConnection();
        RecordingEval eval = new RecordingEval(true); // la sonde dira : capture toujours active
        CaptureReinjector reinjector = new CaptureReinjector(connection, () -> eval);
        reinjector.arm();

        connection.fireLoad();

        assertEquals(0, reinjector.reinjections(),
                "un enregistrement encore vivant ne doit jamais être doublé");
        assertFalse(eval.reinjectedWithGesture());
    }

    @Test
    @DisplayName("désarmé ⇒ un événement de chargement est ignoré")
    void disarmedIgnoresLoadEvents() {
        FakePageConnection connection = new FakePageConnection();
        RecordingEval eval = new RecordingEval(false);
        CaptureReinjector reinjector = new CaptureReinjector(connection, () -> eval);
        reinjector.arm();
        reinjector.disarm();

        connection.fireLoad();

        assertFalse(reinjector.armed());
        assertEquals(0, reinjector.reinjections(), "aucun ré-armement après désarmement");
    }

    @Test
    @DisplayName("cible retrouvée mais injoignable en cours ⇒ avalé + diagnostiqué reinject (best-effort)")
    void unreachableDuringReinjectIsSwallowedAndDiagnosed() {
        FakePageConnection connection = new FakePageConnection();
        // La cible est re-résolue (Eval non-null) mais devient injoignable pendant la sonde/l'injection.
        CaptureReinjector reinjector = new CaptureReinjector(connection, () -> (script, gesture) -> {
            throw new BrowserLinkException(BrowserLinkException.BROWSER_NOT_DETECTED, "injoignable");
        });
        reinjector.arm();

        // Ne doit pas propager l'exception (best-effort strict).
        connection.fireLoad();

        assertEquals(0, reinjector.reinjections());
        assertTrue(RunnerDiag.drain(100).events().stream()
                        .anyMatch(e -> e.cat().equals("capture") && e.code().equals("reinject")
                                && "browser_unreachable".equals(e.fields().get("reason"))),
                "une cible retrouvée puis injoignable doit être diagnostiquée en erreur reinject");
    }

    @Test
    @DisplayName("frameNavigated d'un sous-cadre est ignoré (seul le cadre principal compte)")
    void subFrameNavigationIsIgnored() {
        FakePageConnection connection = new FakePageConnection();
        RecordingEval eval = new RecordingEval(false);
        CaptureReinjector reinjector = new CaptureReinjector(connection, () -> eval);
        reinjector.arm();

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode subFrame = mapper.createObjectNode();
        subFrame.putObject("frame").put("parentId", "parent-123");
        reinjector.onFrameNavigated(subFrame);

        assertEquals(0, reinjector.reinjections(), "une iframe tierce ne doit pas ré-armer la capture");

        ObjectNode mainFrame = mapper.createObjectNode();
        mainFrame.putObject("frame"); // pas de parentId : cadre principal
        reinjector.onFrameNavigated(mainFrame);
        assertEquals(1, reinjector.reinjections(), "le cadre principal ré-arme");
    }

    // ------------------------------------------------------------------ faux

    /** Un {@link CaptureReinjector.Eval} qui enregistre ce qui a été (ré-)injecté et simule la sonde. */
    private static final class RecordingEval implements CaptureReinjector.Eval {
        private final boolean active;
        private final ObjectMapper mapper = new ObjectMapper();
        private boolean reinjectedWithGesture;

        RecordingEval(boolean active) {
            this.active = active;
        }

        @Override
        public JsonNode eval(String script, boolean userGesture) {
            if (script.equals(MeetingTabCapture.ACTIVE_PROBE_SCRIPT)) {
                return mapper.createObjectNode().put("active", active);
            }
            if (script.equals(MeetingTabCapture.START_SCRIPT)) {
                if (userGesture) {
                    reinjectedWithGesture = true;
                }
                return mapper.createObjectNode().put("started", true);
            }
            return null;
        }

        boolean reinjectedWithGesture() {
            return reinjectedWithGesture;
        }
    }

    /** Une connexion de papier qui note les commandes, garde les abonnements et fait feu à volonté. */
    private static final class FakePageConnection implements CdpConnection {
        private final ObjectMapper mapper = new ObjectMapper();
        private final List<String> sent = new ArrayList<>();
        private final Map<String, Consumer<JsonNode>> listeners = new HashMap<>();

        @Override
        public JsonNode send(String method, ObjectNode params) {
            CdpCommands.assertAllowed(method);
            sent.add(method);
            return mapper.createObjectNode();
        }

        @Override
        public void onEvent(String method, Consumer<JsonNode> listener) {
            listeners.put(method, listener);
        }

        void fireLoad() {
            Consumer<JsonNode> listener = listeners.get(CaptureReinjector.LOAD_EVENT);
            if (listener != null) {
                listener.accept(mapper.createObjectNode());
            }
        }

        List<String> sent() {
            return List.copyOf(sent);
        }

        java.util.Set<String> subscribed() {
            return listeners.keySet();
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
            // rien
        }
    }
}
