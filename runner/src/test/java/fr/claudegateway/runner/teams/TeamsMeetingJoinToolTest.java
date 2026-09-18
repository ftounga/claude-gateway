package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.ToolOutcome;
import fr.claudegateway.runner.OperatingSystem;

/**
 * Handler runner {@code teams_meeting_join} (F-128 / SF-128-01b) : navigation de l'onglet Teams du
 * Chrome managé vers l'URL de la réunion. La navigation réelle est validée sur le call réel du PO ;
 * ces tests couvrent le routage, l'assemblage des paramètres et les échecs nommés (CDP simulé).
 */
class TeamsMeetingJoinToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> said = new ArrayList<>();

    /** Un onglet Teams joignable, sur un CDP simulé dont on peut relire les navigations. */
    private TeamsTools toolsLinked(FakeCdpConnection browser) {
        TeamsSession session = new TeamsSession(9222, TeamsAdapters.current(), said::add,
                (port, adapter, say) -> BrowserLink.attach(port, adapter,
                        url -> url.endsWith("/json/version")
                                ? "{\"Browser\":\"Chrome/140.0.0.0\"}"
                                : "[{\"id\":\"1\",\"type\":\"page\",\"url\":\"https://teams.microsoft.com/v2/\","
                                        + "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/d/1\"}]",
                        wsUrl -> browser, say));
        return new TeamsTools(session, millis -> { });
    }

    /** Aucun Chrome managé joignable : l'attache échoue, comme quand le navigateur n'est pas lancé. */
    private TeamsTools toolsUnreachable() {
        TeamsSession session = new TeamsSession(9222, TeamsAdapters.current(), said::add,
                (port, adapter, say) -> {
                    throw new BrowserLinkException(BrowserLinkException.BROWSER_NOT_DETECTED,
                            BrowserLaunchAdvice.forSystem(OperatingSystem.LINUX, port));
                });
        return new TeamsTools(session, millis -> { });
    }

    private ToolOutcome join(TeamsTools tools, String input) throws IOException {
        return tools.execute(TeamsTools.MEETING_JOIN, mapper.readTree(input));
    }

    @Test
    @DisplayName("nominal : navigue l'onglet vers l'URL et rend joined=true")
    void nominalNavigatesTheTab() throws Exception {
        FakeCdpConnection browser = new FakeCdpConnection();
        String url = "https://teams.microsoft.com/l/meetup-join/19%3ameeting_abc";

        ToolOutcome outcome = join(toolsLinked(browser), "{\"url\":\"" + url + "\"}");

        assertTrue(outcome.ok(), "une jonction réussie est un succès d'outil");
        JsonNode json = mapper.readTree(outcome.content());
        assertTrue(json.path("joined").asBoolean(), "joined doit être vrai");
        assertTrue(browser.navigations().contains(url),
                "l'onglet doit avoir été navigué vers l'URL de la réunion");
    }

    @Test
    @DisplayName("URL absente : échec nommé invalid_input, aucune navigation")
    void missingUrlIsRejected() throws Exception {
        FakeCdpConnection browser = new FakeCdpConnection();

        ToolOutcome outcome = join(toolsLinked(browser), "{}");

        assertFalse(outcome.ok());
        assertEquals("invalid_input", outcome.errorCode());
        assertTrue(browser.navigations().isEmpty());
    }

    @Test
    @DisplayName("Chrome managé injoignable : échec nommé browser_unreachable")
    void unreachableChromeIsNamed() throws Exception {
        ToolOutcome outcome = join(toolsUnreachable(),
                "{\"url\":\"https://teams.microsoft.com/l/meetup-join/x\"}");

        assertFalse(outcome.ok());
        assertEquals("browser_unreachable", outcome.errorCode());
    }

    @Test
    @DisplayName("teams_meeting_join n'est PAS un outil d'agent : absent du catalogue")
    void notInAgentCatalog() {
        assertFalse(TeamsTools.CATALOG.contains(TeamsTools.MEETING_JOIN),
                "c'est une commande d'orchestration appelée par le backend, pas un outil de l'agent");
    }
}
