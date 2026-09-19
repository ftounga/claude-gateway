package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.ToolOutcome;
import fr.claudegateway.runner.diag.RunnerDiag;

/**
 * Handlers de capture d'onglet (F-128 / SF-128-02) : routage et échecs nommés (CDP simulé). Le succès
 * de bout en bout (invite de partage, micro, mixage, enregistrement) est validé sur le call réel.
 */
class TeamsMeetingCaptureToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanDiag() {
        RunnerDiag.reset();
    }

    private TeamsTools toolsLinked(MeetingAudioUploader uploader) {
        FakeCdpConnection browser = new FakeCdpConnection();
        TeamsSession session = new TeamsSession(9222, TeamsAdapters.current(), said::add,
                (port, adapter, say) -> BrowserLink.attach(port, adapter,
                        url -> url.endsWith("/json/version")
                                ? "{\"Browser\":\"Chrome/140.0.0.0\"}"
                                : "[{\"id\":\"1\",\"type\":\"page\",\"url\":\"https://teams.microsoft.com/v2/\","
                                        + "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/d/1\"}]",
                        wsUrl -> browser, say));
        TeamsTools tools = new TeamsTools(session, millis -> { });
        return uploader == null ? tools : tools.withMeetingAudio(uploader);
    }

    private ToolOutcome exec(TeamsTools tools, String tool, String input) throws IOException {
        return tools.execute(tool, mapper.readTree(input));
    }

    @Test
    @DisplayName("démarrage : sans navigateur réel, l'échec est nommé (pas de faux succès)")
    void startWithoutRealBrowserFailsNamed() throws Exception {
        ToolOutcome outcome = exec(toolsLinked(null), TeamsTools.MEETING_CAPTURE_START, "{}");
        assertFalse(outcome.ok());
        assertEquals("capture_start_failed", outcome.errorCode());
    }

    @Test
    @DisplayName("arrêt sans uploader : remontée indisponible, nommée")
    void stopWithoutUploaderIsNamed() throws Exception {
        ToolOutcome outcome = exec(toolsLinked(null), TeamsTools.MEETING_CAPTURE_STOP,
                "{\"meeting_id\":\"m1\",\"workspace_id\":\"w1\"}");
        assertFalse(outcome.ok());
        assertEquals("upload_unavailable", outcome.errorCode());
    }

    @Test
    @DisplayName("arrêt sans identifiants : invalid_input")
    void stopWithoutIdsIsRejected() throws Exception {
        MeetingAudioUploader uploader = (workspaceId, meetingId, audio) -> audio.length;
        ToolOutcome outcome = exec(toolsLinked(uploader), TeamsTools.MEETING_CAPTURE_STOP, "{}");
        assertFalse(outcome.ok());
        assertEquals("invalid_input", outcome.errorCode());
    }

    @Test
    @DisplayName("arrêt : sans capture active, l'échec est nommé (pas de remontée)")
    void stopWithoutActiveCaptureIsNamed() throws Exception {
        boolean[] uploaded = { false };
        MeetingAudioUploader uploader = (workspaceId, meetingId, audio) -> {
            uploaded[0] = true;
            return audio.length;
        };
        ToolOutcome outcome = exec(toolsLinked(uploader), TeamsTools.MEETING_CAPTURE_STOP,
                "{\"meeting_id\":\"m1\",\"workspace_id\":\"w1\"}");
        assertFalse(outcome.ok());
        assertEquals("no_active_capture", outcome.errorCode());
        assertFalse(uploaded[0], "rien ne doit remonter sans capture");
    }

    @Test
    @DisplayName("Diag F-132 : un échec de démarrage émet un événement capture/start (motif, jamais le média)")
    void captureStartFailureEmitsDiagEvent() throws Exception {
        exec(toolsLinked(null), TeamsTools.MEETING_CAPTURE_START, "{}");

        assertTrue(RunnerDiag.drain(100).events().stream()
                .anyMatch(e -> e.cat().equals("capture") && e.code().equals("start")
                        && "error".equals(e.fields().get("result"))),
                "un échec de démarrage doit être diagnostiqué");
    }

    @Test
    @DisplayName("Diag F-132 : un arrêt sans capture active émet capture/stop avec le motif no_active_capture")
    void captureStopNoActiveEmitsDiagEvent() throws Exception {
        MeetingAudioUploader uploader = (workspaceId, meetingId, audio) -> audio.length;
        exec(toolsLinked(uploader), TeamsTools.MEETING_CAPTURE_STOP,
                "{\"meeting_id\":\"m1\",\"workspace_id\":\"w1\"}");

        assertTrue(RunnerDiag.drain(100).events().stream()
                .anyMatch(e -> e.cat().equals("capture") && e.code().equals("stop")
                        && "no_active_capture".equals(e.fields().get("reason"))),
                "un arrêt sans capture active doit être diagnostiqué");
    }

    @Test
    @DisplayName("les tools de capture ne sont PAS des outils d'agent : absents du catalogue")
    void notInAgentCatalog() {
        assertFalse(TeamsTools.CATALOG.contains(TeamsTools.MEETING_CAPTURE_START));
        assertFalse(TeamsTools.CATALOG.contains(TeamsTools.MEETING_CAPTURE_STOP));
    }
}
