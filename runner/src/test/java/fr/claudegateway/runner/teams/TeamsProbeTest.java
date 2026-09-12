package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * F-87 / SF-87-03 — <b>le produit doit s'apercevoir qu'il ne sait plus lire Teams avant
 * l'utilisateur</b>.
 *
 * <p>C'est la sonde qui rend honnête tout le reste du volet : l'adaptateur a été écrit sur des
 * échantillons <b>fabriqués</b>, faute de compte Teams de test. Le jour du premier branchement,
 * c'est ici que l'hypothèse est mise à l'épreuve — et ces tests disent ce qu'il se passera dans
 * chacun des cas.</p>
 */
class TeamsProbeTest {

    private static final String MESSAGES_URL =
            "https://teams.microsoft.com/api/chatsvc/emea/v1/users/ME/conversations/19:x/messages";

    private final TeamsAdapter adapter = TeamsAdapters.current();
    private final TeamsProbe probe = new TeamsProbe(adapter);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Tout reconnu : on travaille, et l'état est « relié »")
    void everything_recognized_means_we_work() {
        TeamsProbeResult result = probe.judge(List.of(new ObservedResponse(MESSAGES_URL,
                TeamsPayloadKind.CONVERSATION_MESSAGES,
                TeamsSamples.read("conversation-messages.json"))), "Chrome/140.0.0.0");

        assertEquals(TeamsLinkState.LINKED, result.state());
        assertEquals(TeamsHealthVerdict.FULL, result.health().verdict());
        assertTrue(result.conclusive());
        assertTrue(result.sentence().contains("Teams relié (Chrome/140.0.0.0)"), result.sentence());
        assertEquals("", result.remedy());
    }

    @Test
    @DisplayName("Partiellement reconnu : on travaille ET ON LE DIT")
    void partially_recognized_means_we_work_and_say_so() {
        ObjectNode half = mapper.createObjectNode();
        half.putArray("messages").addObject().put("id", "1").put("from", "u/1")
                .put("imdisplayname", "Paul");

        TeamsProbeResult result = probe.judge(
                List.of(new ObservedResponse(MESSAGES_URL, TeamsPayloadKind.CONVERSATION_MESSAGES,
                        half)), "Chrome/140.0.0.0");

        assertEquals(TeamsLinkState.LINKED, result.state(), "on continue de travailler");
        assertEquals(TeamsHealthVerdict.PARTIAL, result.health().verdict());
        assertTrue(result.sentence().contains("Teams a changé"), result.sentence());
        assertTrue(result.health().missingFields().contains("originalarrivaltime"));
    }

    @Test
    @DisplayName("Rien reconnu : on REFUSE, en nommant ce qui a changé et la version observée")
    void nothing_recognized_means_we_refuse() {
        TeamsProbeResult result = probe.judge(List.of(new ObservedResponse(MESSAGES_URL,
                TeamsPayloadKind.CONVERSATION_MESSAGES,
                TeamsSamples.read("conversation-messages-unknown.json"))), "Chrome/140.0.0.0");

        assertEquals(TeamsLinkState.TEAMS_CHANGED, result.state());
        assertEquals(TeamsHealthVerdict.NONE, result.health().verdict());
        assertTrue(result.sentence().contains("ne sait plus lire"), result.sentence());
        assertTrue(result.sentence().contains("Version observée : v1"), result.sentence());
        assertTrue(result.sentence().contains("Non reconnus : messages"), result.sentence());
        assertTrue(result.remedy().contains("à moitié faux serait pire"), result.remedy());
    }

    @Test
    @DisplayName("Rien OBSERVÉ n'est pas rien RECONNU : on ne crie pas au loup")
    void no_traffic_is_not_a_broken_teams() {
        TeamsProbeResult result = probe.judge(List.of(), "Chrome/140.0.0.0");

        assertEquals(TeamsLinkState.LINKED, result.state());
        assertFalse(result.conclusive());
        assertTrue(result.sentence().contains("Aucune réponse observée"), result.sentence());
        assertTrue(result.sentence().contains("première lecture"), result.sentence());
    }

    @Test
    @DisplayName("Une réponse dont le corps manque ne fausse pas le verdict")
    void a_missing_body_does_not_skew_the_verdict() {
        TeamsProbeResult result = probe.judge(List.of(
                new ObservedResponse(MESSAGES_URL, TeamsPayloadKind.CONVERSATION_MESSAGES, null),
                new ObservedResponse(MESSAGES_URL, TeamsPayloadKind.CONVERSATION_MESSAGES,
                        TeamsSamples.read("conversation-messages.json"))), "");

        assertEquals(TeamsLinkState.LINKED, result.state());
        assertEquals(1, result.observed(), "seule la réponse réellement lue compte");
        assertEquals(TeamsHealthVerdict.FULL, result.health().verdict());
    }

    @Test
    @DisplayName("Liaison impossible : l'état le dit, et le remède est celui de la liaison")
    void a_failed_link_carries_its_remedy() {
        TeamsProbeResult result = TeamsProbe.notLinked(new BrowserLinkException(
                BrowserLinkException.BROWSER_NOT_DETECTED, "Lancez Chrome avec --remote-debugging-port=9222"));

        assertEquals(TeamsLinkState.BROWSER_NOT_DETECTED, result.state());
        assertEquals("Le navigateur du poste n'est pas relié.", result.sentence());
        assertTrue(result.remedy().contains("--remote-debugging-port=9222"));
    }

    @Test
    @DisplayName("La sonde ne demande à la page qu'UN geste, et la vue revient où elle était")
    void the_probe_asks_for_one_gesture_only() {
        FakeCdpConnection browser = new FakeCdpConnection();
        BrowserLink link = BrowserLink.attach(9222, adapter, url -> url.endsWith("/json/version")
                ? "{\"Browser\":\"Chrome/140.0.0.0\"}"
                : "[{\"id\":\"1\",\"type\":\"page\",\"url\":\"https://teams.microsoft.com/v2/\","
                        + "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/devtools/page/1\"}]",
                url -> browser, message -> { });

        probe.probe(link, millis -> { });

        assertEquals(1, browser.sentCommands().stream()
                .filter(CdpCommands.EVALUATE::equals).count());
        browser.sentCommands().forEach(method -> assertTrue(CdpCommands.isAllowed(method), method));
    }
}
