package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
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
 * Entrer réellement en réunion (F-128 / SF-128-16), de bout en bout contre un navigateur de papier qui
 * <b>modélise</b> le bouton « Rejoindre maintenant » et le signal in-call. On prouve, en CI, que
 * {@code teams_meeting_join} clique le bouton (best-effort) puis n'affirme {@code inCall:true} qu'une
 * fois un signal réel observé — et rend {@code inCall:false} au pré-join (plafond). Le comportement
 * navigateur réel reste « À VALIDER SUR CALL RÉEL ».
 */
class TeamsMeetingJoinInCallTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void cleanDiag() {
        RunnerDiag.reset();
    }

    private TeamsTools toolsWith(JoinBrowser browser) {
        TeamsSession session = new TeamsSession(9222, TeamsAdapters.current(), s -> { },
                (port, adapter, say) -> BrowserLink.attach(port, adapter,
                        url -> url.endsWith("/json/version")
                                ? "{\"Browser\":\"Chrome/140.0.0.0\"}"
                                : "[{\"id\":\"1\",\"type\":\"page\",\"url\":\"https://teams.microsoft.com/v2/\","
                                        + "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/d/1\"}]",
                        wsUrl -> browser, say));
        return new TeamsTools(session, millis -> { });
    }

    private JsonNode join(TeamsTools tools) throws IOException {
        ToolOutcome outcome = tools.execute(TeamsTools.MEETING_JOIN,
                mapper.readTree("{\"url\":\"https://teams.microsoft.com/l/meetup-join/19%3ax\"}"));
        assertTrue(outcome.ok(), "le join doit réussir : " + outcome.errorCode());
        return mapper.readTree(outcome.content());
    }

    private boolean diag(String code, String field, Object value) {
        return RunnerDiag.drain(200).events().stream()
                .anyMatch(e -> e.cat().equals("capture") && e.code().equals(code)
                        && value.equals(e.fields().get(field)));
    }

    @Test
    @DisplayName("signal in-call présent : join rend inCall=true et diagnostique capture/incall=in_call")
    void joinReportsInCallWhenSignalPresent() throws Exception {
        JoinBrowser browser = new JoinBrowser().joinReason("absent").inCallFromProbe(1);

        JsonNode json = join(toolsWith(browser));

        assertTrue(json.path("joined").asBoolean(), "joined");
        assertTrue(json.path("inCall").asBoolean(), "in-call réel confirmé → inCall=true");
        assertTrue(diag("incall", "result", "in_call"), "diag capture/incall=in_call");
    }

    @Test
    @DisplayName("pré-join (aucun signal) : join rend inCall=false et diagnostique capture/incall=cap")
    void joinReportsPreJoinWhenNoSignal() throws Exception {
        JoinBrowser browser = new JoinBrowser().joinReason("clicked").inCallFromProbe(-1);

        JsonNode json = join(toolsWith(browser));

        assertTrue(json.path("joined").asBoolean(), "joined");
        assertFalse(json.path("inCall").asBoolean(), "pré-join : pas de signal → inCall=false (best-effort)");
        assertTrue(diag("incall", "result", "cap"), "diag capture/incall=cap (plafond atteint)");
    }

    @Test
    @DisplayName("bouton présent : « Rejoindre maintenant » est cliqué (capture/join_click=clicked)")
    void joinClicksJoinNowWhenButtonPresent() throws Exception {
        // Le clic amène l'in-call : la sonde passe à vrai dès la 1ʳᵉ sonde (après le clic).
        JoinBrowser browser = new JoinBrowser().joinReason("clicked").inCallFromProbe(1);

        JsonNode json = join(toolsWith(browser));

        assertTrue(json.path("inCall").asBoolean());
        assertTrue(diag("join_click", "result", "clicked"), "le bouton présent est cliqué");
        assertTrue(browser.joinScriptRan(), "le script de clic a bien été évalué dans la page");
    }

    @Test
    @DisplayName("déjà in-call / pas de bouton : on continue (capture/join_click=already_in_call|absent)")
    void joinContinuesWhenAlreadyInCall() throws Exception {
        JoinBrowser browser = new JoinBrowser().joinReason("already_in_call").inCallFromProbe(1);

        JsonNode json = join(toolsWith(browser));

        assertTrue(json.path("inCall").asBoolean());
        assertTrue(diag("join_click", "result", "already_in_call"), "déjà in-call → on ne re-clique pas");
    }

    // ------------------------------------------------------------------ navigateur de papier

    /** Un navigateur qui modélise le bouton « Rejoindre maintenant » et le signal in-call. */
    private static final class JoinBrowser implements CdpConnection {
        private final ObjectMapper mapper = new ObjectMapper();
        private final Map<String, Consumer<JsonNode>> listeners = new HashMap<>();
        private String joinReason = "absent";
        private int inCallFromProbe = -1; // -1 = jamais in-call ; sinon, in-call à partir de la Nᵉ sonde
        private int probes;
        private boolean joinScriptRan;
        private boolean open = true;

        JoinBrowser joinReason(String reason) {
            this.joinReason = reason;
            return this;
        }

        JoinBrowser inCallFromProbe(int probe) {
            this.inCallFromProbe = probe;
            return this;
        }

        boolean joinScriptRan() {
            return joinScriptRan;
        }

        @Override
        public JsonNode send(String method, ObjectNode params) {
            CdpCommands.assertAllowed(method);
            if (!open) {
                throw new BrowserLinkException(BrowserLinkException.LINK_LOST, "cible détruite");
            }
            if (!CdpCommands.EVALUATE.equals(method)) {
                return mapper.createObjectNode();
            }
            return evaluate(params.path("expression").asText(""));
        }

        private JsonNode evaluate(String expression) {
            ObjectNode result = mapper.createObjectNode();
            ObjectNode value = result.putObject("result").putObject("value");
            if (expression.contains("location.href")) {
                result.putObject("result").put("value", "https://teams.microsoft.com/l/meetup-join/x");
            } else if (expression.contains("already_in_call")) { // JOIN_NOW_SCRIPT
                joinScriptRan = true;
                value.put("clicked", "clicked".equals(joinReason));
                value.put("reason", joinReason);
            } else if (expression.contains("getAudioTracks")) { // IN_CALL_PROBE_SCRIPT
                probes++;
                boolean inCall = inCallFromProbe >= 0 && probes >= inCallFromProbe;
                value.put("inCall", inCall);
                value.put("by", inCall ? "controls" : "none");
            } else {
                result.removeAll();
                result.putObject("result").put("value", true);
            }
            return result;
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
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
