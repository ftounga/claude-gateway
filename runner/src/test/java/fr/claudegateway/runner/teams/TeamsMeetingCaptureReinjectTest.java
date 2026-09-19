package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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

import fr.claudegateway.runner.ToolOutcome;
import fr.claudegateway.runner.diag.RunnerDiag;

/**
 * Robustesse de la capture à la navigation Teams (F-128 / SF-128-12), de bout en bout contre un
 * navigateur de papier qui <b>modélise</b> le global de capture et sa perte au rechargement.
 *
 * <p>C'est la preuve, en CI, du garde anti-{@code no_active_capture} : {@code start → (navigation qui
 * détruit le global) → événement de chargement → ré-injection → stop} remonte bien de l'audio et des
 * images, là où sans ré-armement l'arrêt échouait. Le comportement navigateur réel reste « À VALIDER
 * SUR CALL RÉEL ».</p>
 */
class TeamsMeetingCaptureReinjectTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanDiag() {
        RunnerDiag.reset();
    }

    private TeamsTools toolsWith(CaptureBrowser browser, MeetingAudioUploader audio,
            MeetingImageUploader images) {
        TeamsSession session = new TeamsSession(9222, TeamsAdapters.current(), said::add,
                (port, adapter, say) -> BrowserLink.attach(port, adapter,
                        url -> url.endsWith("/json/version")
                                ? "{\"Browser\":\"Chrome/140.0.0.0\"}"
                                : "[{\"id\":\"1\",\"type\":\"page\",\"url\":\"https://teams.microsoft.com/v2/\","
                                        + "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/d/1\"}]",
                        wsUrl -> browser, say));
        TeamsTools tools = new TeamsTools(session, millis -> { });
        return tools.withMeetingAudio(audio).withMeetingImages(images);
    }

    private ToolOutcome exec(TeamsTools tools, String tool, String input) throws IOException {
        return tools.execute(tool, mapper.readTree(input));
    }

    @Test
    @DisplayName("start → navigation qui perd le global → stop remonte audio + images (ré-injection)")
    void reinjectionSurvivesNavigation() throws Exception {
        CaptureBrowser browser = new CaptureBrowser();
        int[] uploadedBytes = { 0 };
        int[] uploadedImages = { 0 };
        MeetingAudioUploader audio = (workspaceId, meetingId, bytes) -> {
            uploadedBytes[0] = bytes.length;
            return bytes.length;
        };
        MeetingImageUploader images = (workspaceId, meetingId, image) -> uploadedImages[0]++;
        TeamsTools tools = toolsWith(browser, audio, images);

        // 1) Démarrage : capteur en place, ré-armement armé.
        ToolOutcome start = exec(tools, TeamsTools.MEETING_CAPTURE_START, "{}");
        assertTrue(start.ok(), "le démarrage doit réussir sur le navigateur modélisé");
        assertTrue(browser.captureActive(), "le capteur doit être en place après start");

        // 2) Teams (SPA) recharge la page : le global est détruit ET Page.loadEventFired est émis.
        browser.navigateLosingCapture();
        assertTrue(browser.captureActive(),
                "après la navigation, le ré-armement doit avoir ré-injecté le capteur");

        // 3) Arrêt : trouve un enregistrement actif, remonte audio + images (pas de no_active_capture).
        ToolOutcome stop = exec(tools, TeamsTools.MEETING_CAPTURE_STOP,
                "{\"meeting_id\":\"m1\",\"workspace_id\":\"w1\"}");
        assertTrue(stop.ok(), "l'arrêt doit réussir après ré-injection : " + stop.errorCode());
        assertTrue(uploadedBytes[0] > 0, "audio_bytes > 0 attendu");
        assertTrue(uploadedImages[0] > 0, "image_count > 0 attendu");

        assertTrue(RunnerDiag.drain(200).events().stream()
                        .anyMatch(e -> e.cat().equals("capture") && e.code().equals("reinject")
                                && "ok".equals(e.fields().get("result"))),
                "une ré-injection doit être visible dans le diagnostic F-132");
    }

    @Test
    @DisplayName("sans navigation, une capture qui reste active n'est pas doublée puis s'arrête bien")
    void noNavigationStillStopsCleanly() throws Exception {
        CaptureBrowser browser = new CaptureBrowser();
        int[] uploadedBytes = { 0 };
        MeetingAudioUploader audio = (workspaceId, meetingId, bytes) -> {
            uploadedBytes[0] = bytes.length;
            return bytes.length;
        };
        MeetingImageUploader images = (workspaceId, meetingId, image) -> 1;
        TeamsTools tools = toolsWith(browser, audio, images);

        assertTrue(exec(tools, TeamsTools.MEETING_CAPTURE_START, "{}").ok());
        int startsBefore = browser.starts();
        // Un événement de chargement SANS perte du global : la capture est encore active, on ne double pas.
        browser.fireLoadKeepingCapture();
        assertEquals(startsBefore, browser.starts(), "un enregistrement vivant ne doit pas être doublé");

        ToolOutcome stop = exec(tools, TeamsTools.MEETING_CAPTURE_STOP,
                "{\"meeting_id\":\"m1\",\"workspace_id\":\"w1\"}");
        assertTrue(stop.ok());
        assertTrue(uploadedBytes[0] > 0);
    }

    // ------------------------------------------------------------------ navigateur de papier

    /**
     * Un navigateur qui modélise le global {@code window.__cgMeetingCapture} : {@code start} l'installe,
     * une navigation le détruit, la sonde le lit, {@code stop} rend le média. Assez pour prouver le
     * garde anti-{@code no_active_capture} sans Chrome.
     */
    private static final class CaptureBrowser implements CdpConnection {
        private final ObjectMapper mapper = new ObjectMapper();
        private final Map<String, Consumer<JsonNode>> listeners = new HashMap<>();
        private final String audioBase64 =
                Base64.getEncoder().encodeToString("webm-opus-audio".getBytes(StandardCharsets.UTF_8));
        private final String frameDataUrl = "data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString("jpeg-slide".getBytes(StandardCharsets.UTF_8));

        private boolean active;
        private int starts;

        @Override
        public JsonNode send(String method, ObjectNode params) {
            CdpCommands.assertAllowed(method);
            if (!CdpCommands.EVALUATE.equals(method)) {
                return mapper.createObjectNode();
            }
            return evaluate(params.path("expression").asText(""));
        }

        private JsonNode evaluate(String expression) {
            ObjectNode result = mapper.createObjectNode();
            ObjectNode value = result.putObject("result").putObject("value");
            if (expression.contains("getDisplayMedia")) { // START_SCRIPT (ou ré-injection)
                active = true;
                starts++;
                value.put("started", true);
                value.put("micDenied", false);
            } else if (expression.contains("btoa")) { // STOP_SCRIPT
                if (!active) {
                    value.put("stopped", false);
                    value.put("error", "no_active_capture");
                } else {
                    active = false;
                    value.put("stopped", true);
                    value.put("size", audioBase64.length());
                }
            } else if (expression.contains(".result)")) { // pullScript (média)
                value.put("total", audioBase64.length());
                value.put("chunk", audioBase64);
            } else if (expression.contains("frames.length")) { // framesCountScript
                value.put("count", 1);
            } else if (expression.contains("c.frames[i]")) { // framePullScript
                value.put("total", frameDataUrl.length());
                value.put("chunk", frameDataUrl);
                value.put("t", 0);
            } else if (expression.contains("window.__cgMeetingCapture=null")) { // CLEANUP_SCRIPT
                active = false;
                value.put("cleared", true);
            } else if (expression.contains("return {active")) { // ACTIVE_PROBE_SCRIPT
                value.put("active", active);
            } else {
                result.removeAll();
                result.putObject("result").put("value", true);
            }
            return result;
        }

        /** Teams recharge la page : le global disparaît, puis Page.loadEventFired est émis. */
        void navigateLosingCapture() {
            active = false;
            fire(CaptureReinjector.LOAD_EVENT);
        }

        /** Un événement de chargement sans perte du global (route SPA qui préserve le contexte). */
        void fireLoadKeepingCapture() {
            fire(CaptureReinjector.LOAD_EVENT);
        }

        private void fire(String method) {
            Consumer<JsonNode> listener = listeners.get(method);
            if (listener != null) {
                listener.accept(mapper.createObjectNode());
            }
        }

        boolean captureActive() {
            return active;
        }

        int starts() {
            return starts;
        }

        @Override
        public void onEvent(String method, Consumer<JsonNode> listener) {
            listeners.put(method, listener);
        }

        @Override
        public void onSessionEvent(String method,
                java.util.function.BiConsumer<String, JsonNode> listener) {
            listeners.put(method, params -> listener.accept("", params));
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
