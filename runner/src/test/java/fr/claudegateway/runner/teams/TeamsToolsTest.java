package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.ToolOutcome;

/**
 * F-87 / SF-87-03 — {@code teams_status}, l'outil qui sert <b>deux</b> consommateurs : l'indicateur
 * de la barre du terminal, et l'agent (F-88).
 *
 * <p>Le point de conception vérifié ici : une liaison impossible reste un <b>succès</b> d'outil
 * porteur d'un état négatif et de son remède. Une erreur d'outil ferait dire à l'agent « je n'ai pas
 * réussi », là où il faut dire « lancez votre navigateur comme ceci ».</p>
 */
class TeamsToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> said = new ArrayList<>();

    @Test
    @DisplayName("Navigateur absent : succès d'outil, état négatif, ET la commande à coller")
    void a_missing_browser_is_a_state_not_a_failure() throws Exception {
        TeamsTools tools = toolsThatCannotAttach();

        ToolOutcome outcome = tools.execute(TeamsTools.STATUS, null);

        assertTrue(outcome.ok(), "un navigateur à lancer n'est pas une panne de l'outil");
        JsonNode json = mapper.readTree(outcome.content());
        assertEquals("BROWSER_NOT_DETECTED", json.path("state").asText());
        assertEquals("Teams : navigateur non détecté", json.path("label").asText());
        assertTrue(json.path("remedy").asText().contains("--remote-debugging-port=9222"));
        assertTrue(json.path("text").asText().contains("--user-data-dir"));
    }

    @Test
    @DisplayName("Relié : l'état, la santé et la version observée sont rendus")
    void a_working_link_renders_its_health() throws Exception {
        TeamsTools tools = toolsLinkedTo(TeamsSamples.read("conversation-messages.json"));

        JsonNode json = mapper.readTree(tools.execute(TeamsTools.STATUS, null).content());

        assertEquals("LINKED", json.path("state").asText());
        assertEquals("FULL", json.path("health").path("verdict").asText());
        assertEquals("v1", json.path("adapter").asText());
        assertTrue(json.path("conclusive").asBoolean());
        assertEquals("v1", json.path("health").path("observedApiVersions").get(0).asText());
    }

    @Test
    @DisplayName("Teams a changé : l'outil le dit, et dit pourquoi il ne produira rien")
    void a_broken_teams_is_announced() throws Exception {
        TeamsTools tools = toolsLinkedTo(TeamsSamples.read("conversation-messages-unknown.json"));

        JsonNode json = mapper.readTree(tools.execute(TeamsTools.STATUS, null).content());

        assertEquals("TEAMS_CHANGED", json.path("state").asText());
        assertEquals("Teams a changé", json.path("label").asText());
        assertTrue(json.path("text").asText().contains("à moitié faux serait pire"));
    }

    @Test
    @DisplayName("L'annonce de premier usage voyage avec le PREMIER résultat, et une seule fois")
    void the_first_use_notice_travels_once() throws Exception {
        TeamsTools tools = toolsLinkedTo(TeamsSamples.read("conversation-messages.json"));

        String first = mapper.readTree(tools.execute(TeamsTools.STATUS, null).content())
                .path("text").asText();
        String second = mapper.readTree(tools.execute(TeamsTools.STATUS, null).content())
                .path("text").asText();

        assertTrue(first.contains("Première utilisation de Teams"), first);
        assertFalse(second.contains("Première utilisation de Teams"), second);
    }

    @Test
    @DisplayName("--no-teams : l'outil refuse en disant pourquoi, et la capacité n'est pas annoncée")
    void disabled_says_why() throws Exception {
        TeamsTools tools = TeamsTools.disabled("Le volet Teams est désactivé sur cette machine "
                + "(--no-teams).");

        JsonNode json = mapper.readTree(tools.execute(TeamsTools.STATUS, null).content());

        assertFalse(tools.enabled());
        assertEquals("BROWSER_NOT_DETECTED", json.path("state").asText());
        assertTrue(json.path("remedy").asText().contains("--no-teams"));
    }

    @Test
    @DisplayName("Un outil Teams inconnu est refusé, pas deviné")
    void an_unknown_teams_tool_is_refused() {
        ToolOutcome outcome = toolsThatCannotAttach().execute("teams_invente", null);

        assertFalse(outcome.ok());
        assertEquals("unsupported_tool", outcome.errorCode());
    }

    // ------------------------------------------------------------------ montages

    private TeamsTools toolsThatCannotAttach() {
        TeamsSession session = new TeamsSession(9222, TeamsAdapters.current(), said::add,
                (port, adapter, say) -> {
                    throw new BrowserLinkException(BrowserLinkException.BROWSER_NOT_DETECTED,
                            BrowserLaunchAdvice.forSystem(
                                    fr.claudegateway.runner.OperatingSystem.LINUX, port));
                });
        return new TeamsTools(session, millis -> { });
    }

    private TeamsTools toolsLinkedTo(JsonNode body) {
        TeamsSession session = new TeamsSession(9222, TeamsAdapters.current(), said::add,
                (port, adapter, say) -> {
                    FakeCdpConnection browser = new FakeCdpConnection();
                    BrowserLink link = BrowserLink.attach(port, adapter,
                            url -> url.endsWith("/json/version")
                                    ? "{\"Browser\":\"Chrome/140.0.0.0\"}"
                                    : "[{\"id\":\"1\",\"type\":\"page\","
                                            + "\"url\":\"https://teams.microsoft.com/v2/\","
                                            + "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/d/1\"}]",
                            wsUrl -> browser, say);
                    browser.emitResponse("req-1", "https://teams.microsoft.com/api/chatsvc/emea/v1/"
                            + "users/ME/conversations/19:x/messages", body.toString());
                    return link;
                });
        return new TeamsTools(session, millis -> { });
    }
}
