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
 * F-88 / SF-88-02 — <b>les trois gisements d'un engagement</b>, et pourquoi il les faut tous les
 * trois.
 *
 * <p>La <b>mention</b> passe par le flux d'activité, le <b>nom écrit en clair</b> par l'index de
 * Teams : deux choses que le service <b>calcule déjà</b>, et qu'on ne refait pas. Le troisième — ses
 * <b>propres promesses</b>, « je te l'envoie demain » — ne porte ni mention ni nom : aucune
 * recherche par mot-clé ne le trouve, il faut ouvrir les fils. C'est ce qu'a livré SF-88-01.</p>
 */
class TeamsGisementsTest {

    private static final String ACTIVITY_URL =
            "https://teams.microsoft.com/api/mt/emea/beta/users/me/activityfeed";
    private static final String SEARCH_URL =
            "https://teams.microsoft.com/api/searchservice/emea/v1/search/messages";

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode ask() {
        return mapper.createObjectNode();
    }

    private static boolean hasGap(JsonNode json, String kind) {
        for (JsonNode gap : json.path("gaps")) {
            if (kind.equals(gap.path("kind").asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode call(TeamsTools tools, String tool, ObjectNode input) throws Exception {
        ToolOutcome outcome = tools.execute(tool, input);
        assertTrue(outcome.ok(), "un outil de lecture rend un état, pas une panne");
        return mapper.readTree(outcome.content());
    }

    // ------------------------------------------------------------ gisement 1 : la mention

    @Test
    @DisplayName("teams_mentions lit le flux d'activité — et n'y prend QUE les mentions")
    void the_activity_feed_yields_mentions_only() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("a1", ACTIVITY_URL, "activity-feed.json");

        JsonNode json = call(teams.tools(), TeamsTools.MENTIONS,
                ask().put("from", "2026-09-01").put("to", "2026-09-30"));

        assertEquals("teams_mentions", json.path("tool").asText());
        assertTrue(json.path("mentions").size() >= 1, json.toString());
        json.path("mentions").forEach(mention -> {
            assertFalse(mention.path("preview").asText().contains("réaction n'est pas une mention"),
                    "une réaction n'est pas une mention : " + mention);
            assertTrue(mention.has("author"));
            assertTrue(mention.has("at"));
            assertTrue(mention.has("webUrl"));
        });
    }

    @Test
    @DisplayName("Mentions triées de la plus récente à la plus ancienne")
    void mentions_come_back_newest_first() throws Exception {
        PaperTeams teams = new PaperTeams().already("a1", ACTIVITY_URL, "activity-feed.json");

        JsonNode json = call(teams.tools(), TeamsTools.MENTIONS,
                ask().put("from", "2026-09-01").put("to", "2026-09-30"));

        for (int index = 1; index < json.path("mentions").size(); index++) {
            String newer = json.path("mentions").get(index - 1).path("at").asText();
            String older = json.path("mentions").get(index).path("at").asText();
            assertTrue(newer.compareTo(older) >= 0, newer + " devrait précéder " + older);
        }
    }

    @Test
    @DisplayName("Utilisateur relié inconnu : on rend ce que le flux porte, ET on dit qu'on n'a pas"
            + " pu le vérifier")
    void an_unknown_linked_user_is_said_not_assumed() throws Exception {
        PaperTeams teams = new PaperTeams().already("a1", ACTIVITY_URL, "activity-feed.json");

        JsonNode json = call(teams.tools(), TeamsTools.MENTIONS,
                ask().put("from", "2026-09-01").put("to", "2026-09-30"));

        assertTrue(json.path("text").asText().contains("n'a pas encore été identifié"),
                json.path("text").asText());
    }

    @Test
    @DisplayName("Aucune mention observée n'est pas « on ne vous a rien demandé » : c'est un manque")
    void no_mention_observed_is_a_named_gap() throws Exception {
        PaperTeams teams = new PaperTeams();

        JsonNode json = call(teams.tools(), TeamsTools.MENTIONS, ask());

        assertEquals(0, json.path("mentions").size());
        assertEquals("NOTHING_OBSERVED", json.path("gaps").get(0).path("kind").asText());
    }

    // ------------------------------------------------------------ gisement 2 : le nom en clair

    @Test
    @DisplayName("teams_search pose la question dans le champ de Teams, lit ce que l'index sert,"
            + " puis REMET le champ tel qu'il était")
    void searching_asks_teams_index_and_puts_the_field_back() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.hasSearchField("ce que l'utilisateur tapait");
        teams.browser.deliverOnSearch("s1", SEARCH_URL,
                TeamsSamples.read("search-results.json").toString());

        JsonNode json = call(teams.tools(), TeamsTools.SEARCH,
                ask().put("query", "Francky").put("from", "2026-09-01").put("to", "2026-09-30"));

        assertEquals(java.util.List.of("Francky"), teams.browser.searches());
        assertEquals("ce que l'utilisateur tapait", teams.browser.searchField(),
                "le champ de l'utilisateur doit être remis tel qu'il était");
        assertEquals(2, json.path("results").size(), json.toString());
        assertTrue(json.path("viewport").asText().contains("le champ a été remis"),
                json.path("viewport").asText());
        assertTrue(json.path("results").get(0).path("text").asText().contains("MFA"));
    }

    @Test
    @DisplayName("Pas de champ de recherche : ZÉRO résultat, un manque nommé, ET la phrase qui dit"
            + " que ce n'est pas « il n'y a rien »")
    void a_search_that_could_not_be_asked_never_looks_like_an_empty_answer() throws Exception {
        PaperTeams teams = new PaperTeams(); // aucune hasSearchField : la page n'en a pas

        JsonNode json = call(teams.tools(), TeamsTools.SEARCH, ask().put("query", "Francky"));

        assertEquals(0, json.path("results").size());
        assertTrue(json.path("text").asText().contains("ne veut donc PAS dire qu'il n'y a rien"),
                json.path("text").asText());
        assertTrue(json.path("text").asText().contains("tapez « Francky »"),
                json.path("text").asText());
    }

    @Test
    @DisplayName("Une recherche sans question est un manque nommé, jamais une recherche à vide")
    void a_search_without_a_query_is_a_named_gap() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.hasSearchField("");

        JsonNode json = call(teams.tools(), TeamsTools.SEARCH, ask());

        assertEquals(0, json.path("results").size());
        assertEquals("MISSING_FIELD", json.path("gaps").get(0).path("kind").asText());
        assertEquals("query", json.path("gaps").get(0).path("detail").asText());
        assertTrue(teams.browser.searches().isEmpty(), "rien ne doit être tapé dans Teams");
    }

    // ------------------------------------------------------------ les réunions

    @Test
    @DisplayName("teams_find_meetings rend « enregistrée » et « transcription annoncée » — les deux"
            + " champs qui disent si la suite est possible")
    void meetings_say_whether_the_rest_is_possible() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("m1", TeamsSamples.MEETINGS_URL, "meetings.json");

        JsonNode json = call(teams.tools(), TeamsTools.FIND_MEETINGS,
                ask().put("from", "2026-09-01").put("to", "2026-09-30"));

        assertEquals(1, json.path("meetings").size(), json.toString());
        JsonNode meeting = json.path("meetings").get(0);
        assertEquals("MTG-FABRIQUE-0001", meeting.path("id").asText());
        assertTrue(meeting.path("recorded").asBoolean());
        assertTrue(meeting.path("transcriptAvailable").asBoolean());
    }

    @Test
    @DisplayName("On retrouve une réunion par son sujet comme par un participant")
    void meetings_are_found_by_subject_or_participant() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("m1", TeamsSamples.MEETINGS_URL, "meetings.json");
        TeamsTools tools = teams.tools();

        JsonNode bySubject = call(tools, TeamsTools.FIND_MEETINGS,
                ask().put("query", "migration").put("from", "2026-09-01").put("to", "2026-09-30"));
        JsonNode byPerson = call(tools, TeamsTools.FIND_MEETINGS,
                ask().put("query", "claire").put("from", "2026-09-01").put("to", "2026-09-30"));

        assertEquals(1, bySubject.path("meetings").size());
        assertEquals(0, byPerson.path("meetings").size(),
                "Claire ne participe pas à cette réunion dans l'échantillon");
        assertEquals("NOTHING_OBSERVED", byPerson.path("gaps").get(0).path("kind").asText());
    }

    @Test
    @DisplayName("teams_meeting_transcript rend les répliques dans l'ordre, avec leur locuteur — et"
            + " D1 paraît UNE FOIS par réunion")
    void a_transcript_is_ordered_and_announced_once() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("m1", TeamsSamples.MEETINGS_URL, "meetings.json")
                .already("t1", TeamsSamples.TRANSCRIPT_URL, "transcript.json");
        TeamsTools tools = teams.tools();

        JsonNode first = call(tools, TeamsTools.MEETING_TRANSCRIPT,
                ask().put("meeting_id", "MTG-FABRIQUE-0001"));
        JsonNode second = call(tools, TeamsTools.MEETING_TRANSCRIPT,
                ask().put("meeting_id", "MTG-FABRIQUE-0001"));

        assertEquals(2, first.path("cues").size(),
                "la réplique sans horodatage ne doit pas être rendue");
        assertEquals("Paul Durand", first.path("cues").get(0).path("speaker").asText());
        String earlier = first.path("cues").get(0).path("at").asText();
        String later = first.path("cues").get(1).path("at").asText();
        assertTrue(earlier.compareTo(later) < 0, earlier + " devrait précéder " + later);
        assertTrue(first.path("notice").asText().contains("Portée de cette lecture"),
                first.path("notice").asText());
        assertFalse(second.has("notice"), "une annonce répétée cesse d'être lue (D1)");
    }

    @Test
    @DisplayName("Transcription jamais observée : zéro réplique, un manque nommé, et le remède")
    void a_missing_transcript_is_a_named_gap() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("m1", TeamsSamples.MEETINGS_URL, "meetings.json");

        JsonNode json = call(teams.tools(), TeamsTools.MEETING_TRANSCRIPT,
                ask().put("meeting_id", "MTG-FABRIQUE-0001"));

        assertEquals(0, json.path("cues").size());
        // Deux manques cohabitent ici, et c'est voulu : la réunion sans horodatage de l'échantillon
        // (MISSING_FIELD) et la transcription jamais servie. Les deux doivent se voir.
        assertTrue(hasGap(json, "NOTHING_OBSERVED"), json.path("gaps").toString());
        assertTrue(json.path("text").asText().contains("Ouvrez la transcription dans Teams"),
                json.path("text").asText());
        assertFalse(json.has("notice"), "rien n'a été lu : il n'y a pas de portée à déclarer");
    }

    @Test
    @DisplayName("teams_meeting_recording DIT qu'il ne télécharge pas, et pourquoi — l'agent ne doit"
            + " pas pouvoir croire qu'un fichier existe")
    void the_recording_tool_says_what_it_does_not_do() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("m1", TeamsSamples.MEETINGS_URL, "meetings.json");

        JsonNode json = call(teams.tools(), TeamsTools.MEETING_RECORDING,
                ask().put("meeting_id", "MTG-FABRIQUE-0001"));

        assertFalse(json.path("downloaded").asBoolean());
        assertTrue(json.path("available").asBoolean());
        assertTrue(json.path("text").asText().contains("Je ne l'ai PAS téléchargé"),
                json.path("text").asText());
        assertTrue(json.path("whyNotDownloaded").asText().contains("adresse signée"),
                json.path("whyNotDownloaded").asText());
        assertTrue(json.has("destination"));
    }

    @Test
    @DisplayName("Une réunion inconnue du registre : un manque nommé, pas un silence")
    void an_unknown_meeting_is_a_named_gap() throws Exception {
        PaperTeams teams = new PaperTeams();

        JsonNode json = call(teams.tools(), TeamsTools.MEETING_RECORDING,
                ask().put("meeting_id", "MTG-INCONNUE"));

        assertTrue(json.path("available").isNull());
        assertEquals("NOTHING_OBSERVED", json.path("gaps").get(0).path("kind").asText());
        assertTrue(json.path("text").asText().contains("Je ne connais pas cette réunion"));
    }

    // ------------------------------------------------------------ règles de forme du catalogue

    @Test
    @DisplayName("Les cinq outils portent l'enveloppe commune, celle que F-89 affichera")
    void every_tool_carries_the_common_envelope() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("m1", TeamsSamples.MEETINGS_URL, "meetings.json")
                .already("a1", ACTIVITY_URL, "activity-feed.json");
        TeamsTools tools = teams.tools();

        for (String tool : java.util.List.of(TeamsTools.MENTIONS, TeamsTools.SEARCH,
                TeamsTools.FIND_MEETINGS, TeamsTools.MEETING_TRANSCRIPT,
                TeamsTools.MEETING_RECORDING)) {
            JsonNode json = call(tools, tool, ask().put("query", "x").put("meeting_id", "x"));
            assertEquals(tool, json.path("tool").asText());
            assertTrue(json.has("text"), tool + " doit porter la phrase à citer");
            assertTrue(json.has("window"), tool + " doit porter la fenêtre lue");
            assertTrue(json.has("gaps"), tool + " doit porter ce qu'il n'a pas pu lire");
            assertTrue(json.has("health"), tool + " doit porter la santé de l'adaptateur");
        }
    }

    @Test
    @DisplayName("Navigateur non relié : les cinq outils rendent un état et un remède, jamais une"
            + " panne")
    void an_unlinked_browser_is_a_state_for_every_tool() throws Exception {
        TeamsTools tools = new TeamsTools(new TeamsSession(9222, TeamsAdapters.current(),
                message -> { }, (port, adapter, say) -> {
                    throw new BrowserLinkException(BrowserLinkException.BROWSER_NOT_DETECTED,
                            BrowserLaunchAdvice.forSystem(
                                    fr.claudegateway.runner.OperatingSystem.LINUX, port));
                }), millis -> { });

        for (String tool : java.util.List.of(TeamsTools.MENTIONS, TeamsTools.SEARCH,
                TeamsTools.FIND_MEETINGS, TeamsTools.MEETING_TRANSCRIPT,
                TeamsTools.MEETING_RECORDING)) {
            JsonNode json = call(tools, tool, ask().put("query", "x"));
            assertEquals("BROWSER_NOT_DETECTED", json.path("linkState").asText(), tool);
            assertTrue(json.path("remedy").asText().contains("--remote-debugging-port=9222"), tool);
        }
    }

    @Test
    @DisplayName("Aucun jeton, aucun cookie ne ressort d'une recherche empoisonnée")
    void no_secret_leaves_a_search() {
        PaperTeams teams = new PaperTeams();
        teams.browser.hasSearchField("");
        teams.browser.deliverOnSearch("s1", SEARCH_URL,
                TeamsSamples.read("conversation-messages-secrets.json").toString());

        String rendered = teams.tools()
                .execute(TeamsTools.SEARCH, ask().put("query", "MFA")).content();

        assertFalse(rendered.contains("SECRET"), rendered);
    }

    @Test
    @DisplayName("Le catalogue annoncé est celui que les outils savent exécuter")
    void the_catalog_matches_what_the_tools_answer() {
        PaperTeams teams = new PaperTeams();
        TeamsTools tools = teams.tools();

        // F-90 / SF-90-03 a ajouté les deux outils de captures ; F-91 / SF-91-02 les trois de
        // l'enregistrement local : le catalogue en porte treize.
        assertEquals(13, TeamsTools.CATALOG.size());
        TeamsTools.CATALOG.forEach(tool -> assertTrue(tools.execute(tool, ask()).ok(),
                tool + " est annoncé au catalogue : il doit répondre"));
        assertFalse(tools.execute("teams_invente", ask()).ok());
    }

    @Test
    @DisplayName("La recherche de SF-88-02 est posée dans la page ; cookies et stockage refusés")
    void search_stays_in_page_and_cookies_stay_refused() {
        // SF-108-01 a ouvert les gestes d'action, gardés par domaine. Ce que ce test garde : la
        // recherche n'ouvre aucun accès aux cookies ni au stockage — ce refus-là ne se rouvre pas.
        assertFalse(CdpCommands.isAllowed("Network.getAllCookies"));
        assertFalse(CdpCommands.isAllowed("Storage.getCookies"),
                "la question est posée DANS la page, jamais en touchant au stockage de la session");
    }
}
