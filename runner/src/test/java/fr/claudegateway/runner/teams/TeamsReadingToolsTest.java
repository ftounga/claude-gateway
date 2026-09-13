package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.ToolOutcome;

/**
 * F-88 / SF-88-01 — les deux premiers outils du catalogue, vus <b>de l'extérieur</b> : ce que l'agent
 * reçoit réellement.
 *
 * <p>Ce qui est vérifié ici est l'<b>enveloppe</b>, celle que F-89 affichera : le résultat, la
 * fenêtre réellement lue, les manques, la santé — et la phrase à citer. Un outil ne peut pas rendre
 * une liste sans dire, à côté, ce qu'il n'a pas pu lire.</p>
 */
class TeamsReadingToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode ask() {
        return mapper.createObjectNode();
    }

    private JsonNode call(TeamsTools tools, String tool, ObjectNode input) throws Exception {
        ToolOutcome outcome = tools.execute(tool, input);
        assertTrue(outcome.ok(), "un outil de lecture rend un état, pas une panne : "
                + outcome.errorMessage());
        return mapper.readTree(outcome.content());
    }

    @Test
    @DisplayName("teams_read_conversation rend les messages, la fenêtre réellement lue, les manques"
            + " et la santé")
    void reading_a_thread_renders_the_full_envelope() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages.json")
                .onScroll("r2", PaperTeams.MESSAGES_URL, "conversation-messages-page2.json");

        JsonNode json = call(teams.tools(), TeamsTools.READ_CONVERSATION,
                ask().put("conversation_id", PaperTeams.THREAD).put("from", "2026-08-01"));

        assertEquals("teams_read_conversation", json.path("tool").asText());
        assertEquals("LINKED", json.path("linkState").asText());
        assertEquals("v1", json.path("adapter").asText());
        assertEquals(5, json.path("messages").size());
        assertTrue(json.path("window").has("actualFrom"));
        assertTrue(json.path("window").has("complete"));
        assertTrue(json.has("gaps"));
        assertEquals("FULL", json.path("health").path("verdict").asText());
        assertTrue(json.path("text").asText().contains("messages lus"), json.path("text").asText());
        JsonNode first = json.path("messages").get(0);
        assertTrue(first.has("author"));
        assertTrue(first.has("sentAt"));
        assertTrue(first.has("mentionsMe"));
    }

    @Test
    @DisplayName("D1 — la déclaration de portée paraît UNE FOIS par fil, jamais à chaque tour")
    void the_scope_notice_is_said_once_per_thread() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages.json");
        TeamsTools tools = teams.tools();

        JsonNode first = call(tools, TeamsTools.READ_CONVERSATION,
                ask().put("conversation_id", PaperTeams.THREAD));
        JsonNode second = call(tools, TeamsTools.READ_CONVERSATION,
                ask().put("conversation_id", PaperTeams.THREAD));

        assertTrue(first.path("notice").asText().contains("Portée de cette lecture"),
                first.path("notice").asText());
        assertTrue(first.path("notice").asText().contains("supprimé avec lui"));
        assertFalse(second.has("notice"), "une annonce répétée cesse d'être lue (D1)");
    }

    @Test
    @DisplayName("D4 — un plafond déraisonnable est ramené dans les bornes, ET c'est dit")
    void an_unreasonable_cap_is_clamped_and_said() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages.json");

        JsonNode json = call(teams.tools(), TeamsTools.READ_CONVERSATION,
                ask().put("conversation_id", PaperTeams.THREAD).put("max_messages", 999_999));

        assertEquals(TeamsAsk.MAX_CAP, json.path("window").path("cap").asInt());
        assertTrue(json.path("text").asText().contains("ramené à " + TeamsAsk.MAX_CAP),
                json.path("text").asText());
    }

    @Test
    @DisplayName("D4 — une période de départ incompréhensible retombe sur le défaut, ET c'est dit")
    void an_unreadable_from_falls_back_and_says_so() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages.json");

        JsonNode json = call(teams.tools(), TeamsTools.READ_CONVERSATION,
                ask().put("conversation_id", PaperTeams.THREAD).put("from", "la semaine du chat"));

        assertTrue(json.path("text").asText().contains("plafond par défaut de 7 jours"),
                json.path("text").asText());
    }

    @Test
    @DisplayName("Aucun jeton, aucun cookie ne ressort d'une page empoisonnée")
    void no_secret_ever_leaves_the_reading() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages-secrets.json");

        String rendered = teams.tools()
                .execute(TeamsTools.READ_CONVERSATION,
                        ask().put("conversation_id", PaperTeams.THREAD))
                .content();

        assertFalse(rendered.contains("SECRET"), rendered);
        assertFalse(rendered.toLowerCase(java.util.Locale.ROOT).contains("skypetoken"), rendered);
        assertFalse(rendered.toLowerCase(java.util.Locale.ROOT).contains("authorization"), rendered);
    }

    @Test
    @DisplayName("Navigateur non relié : SUCCÈS d'outil portant l'état, le remède, et zéro message")
    void an_unlinked_browser_is_a_state_not_a_failure() throws Exception {
        TeamsTools tools = new TeamsTools(new TeamsSession(9222, TeamsAdapters.current(),
                message -> { }, (port, adapter, say) -> {
                    throw new BrowserLinkException(BrowserLinkException.BROWSER_NOT_DETECTED,
                            BrowserLaunchAdvice.forSystem(
                                    fr.claudegateway.runner.OperatingSystem.LINUX, port));
                }), millis -> { });

        JsonNode json = call(tools, TeamsTools.READ_CONVERSATION, ask());

        assertEquals("BROWSER_NOT_DETECTED", json.path("linkState").asText());
        assertTrue(json.path("remedy").asText().contains("--remote-debugging-port=9222"));
        assertEquals(0, json.path("messages").size());
        assertTrue(json.path("gaps").get(0).path("kind").asText().equals("NOTHING_OBSERVED"));
    }

    @Test
    @DisplayName("--no-teams : l'outil de lecture le dit, et ne prétend rien avoir lu")
    void disabled_reading_says_why() throws Exception {
        TeamsTools tools = TeamsTools.disabled("Le volet Teams est désactivé (--no-teams).");

        JsonNode json = call(tools, TeamsTools.READ_CONVERSATION, ask());

        assertEquals("BROWSER_NOT_DETECTED", json.path("linkState").asText());
        assertTrue(json.path("text").asText().contains("--no-teams"));
        assertEquals(0, json.path("messages").size());
    }

    // ------------------------------------------------------------------ teams_find_conversations

    @Test
    @DisplayName("teams_find_conversations classe par dernière activité")
    void conversations_come_back_by_last_activity() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("c1", TeamsSamples.CONVERSATIONS_URL, "conversation-list.json");

        JsonNode json = call(teams.tools(), TeamsTools.FIND_CONVERSATIONS, ask());

        assertTrue(json.path("conversations").size() >= 2);
        String first = json.path("conversations").get(0).path("lastActivityAt").asText();
        String second = json.path("conversations").get(1).path("lastActivityAt").asText();
        assertTrue(first.compareTo(second) >= 0, first + " devrait précéder " + second);
    }

    @Test
    @DisplayName("On trouve une conversation par le NOM D'UN PARTICIPANT autant que par son sujet —"
            + " un tête-à-tête n'a pas de sujet")
    void a_thread_is_found_by_participant_name() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("c1", TeamsSamples.CONVERSATIONS_URL, "conversation-list.json");
        TeamsTools tools = teams.tools();

        JsonNode byPerson = call(tools, TeamsTools.FIND_CONVERSATIONS, ask().put("query", "paul"));
        JsonNode byTopic = call(tools, TeamsTools.FIND_CONVERSATIONS, ask().put("query", "MFA"));

        assertTrue(byPerson.path("conversations").size() >= 1, byPerson.toString());
        assertEquals(1, byTopic.path("conversations").size(), byTopic.toString());
        assertEquals("Migration MFA", byTopic.path("conversations").get(0).path("label").asText());
    }

    @Test
    @DisplayName("Le rapprochement est insensible aux accents et à la casse")
    void matching_ignores_case_and_accents() {
        assertEquals(TeamsTools.fold("Migration MFA"), TeamsTools.fold("migration mfa"));
        assertEquals(TeamsTools.fold("Réunion"), TeamsTools.fold("reunion"));
    }

    @Test
    @DisplayName("La lecture de F-88 reste faite par le réseau ; les cookies restent refusés")
    void reading_stays_network_and_cookies_stay_refused() {
        // SF-108-01 a ouvert les GESTES D'ACTION (navigation, saisie), gardés par domaine ailleurs.
        // Ce que ce test garde encore : la LECTURE ne s'appuie sur aucune commande cookie/stockage,
        // et ces refus-là ne se sont pas rouverts.
        assertFalse(CdpCommands.isAllowed("Network.getAllCookies"));
        assertFalse(CdpCommands.isAllowed("Network.getCookies"));
        assertFalse(CdpCommands.isAllowed("Storage.getCookies"));
    }

    @Test
    @DisplayName("Rien d'observé n'est pas « il n'y a rien » : c'est un manque nommé")
    void nothing_observed_is_a_named_gap() throws Exception {
        PaperTeams teams = new PaperTeams();

        JsonNode json = call(teams.tools(), TeamsTools.FIND_CONVERSATIONS,
                ask().put("query", "introuvable"));

        assertEquals(0, json.path("conversations").size());
        assertEquals("NOTHING_OBSERVED", json.path("gaps").get(0).path("kind").asText());
        assertTrue(json.path("text").asText().contains("rien d'observé depuis le rattachement"),
                json.path("text").asText());
    }
}
