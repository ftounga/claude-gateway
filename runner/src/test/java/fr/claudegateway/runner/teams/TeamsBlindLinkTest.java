package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.ToolOutcome;

/**
 * F-89 / SF-89-05 — <b>relié mais aveugle : le dire, et regarder aussi les workers</b>.
 *
 * <p>Constat de production : {@code teams_status} « relié », tous les outils de lecture à zéro avec
 * « rien d'observé ». Le premier relevé réel (2026-09-13) a montré pourquoi, en partie : la liste des
 * réunions n'est servie que par un <b>worker</b>, et plusieurs chemins de Teams ne sont pas classés.
 * Ces tests tiennent les trois réponses : écouter les workers, chiffrer ce qui a été vu, provoquer le
 * chargement avant de conclure — sans jamais laisser sortir un tenant, un identifiant ou une requête.</p>
 */
class TeamsBlindLinkTest {

    private static final String WORKER_URL = "https://teams.microsoft.com/v2/worker/precompiled-web-worker.js";
    /** Chemin du relevé réel : la liste des réunions, vue seulement depuis un worker. */
    private static final String SCHEDULING_URL =
            "https://teams.microsoft.com/api/mt/emea/v1/schedulingService/meetings?from=2026-09-01";
    private static final String CALENDAR_EVENT_URL =
            "https://teams.microsoft.com/api/mt/emea/v2.0/me/calendars/events/iCalUId/040000008200E0007?x=1";
    private static final String COLLAB_URL =
            "https://teams.microsoft.com/api/mcps/eu/collab/readcollabobject/V2/aa11/bb22/cc33";

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode call(TeamsTools tools, String tool, ObjectNode input) throws Exception {
        ToolOutcome outcome = tools.execute(tool, input);
        assertTrue(outcome.ok(), outcome.errorMessage());
        return mapper.readTree(outcome.content());
    }

    private ObjectNode ask() {
        return mapper.createObjectNode();
    }

    private ObjectNode wideWindow() {
        return ask().put("from", "2026-01-01T00:00:00Z").put("to", "2027-01-01T00:00:00Z");
    }

    // ------------------------------------------------------------------ (a) les workers aussi

    @Test
    @DisplayName("(a) une lecture écoute les workers : la liste des réunions servie par un worker est rendue")
    void a_worker_served_meeting_list_is_read() throws Exception {
        PaperTeams teams = new PaperTeams();
        TeamsTools tools = teams.tools();
        call(tools, TeamsTools.STATUS, ask());

        teams.browser.emitAttached("W1", "worker", WORKER_URL);
        teams.browser.emitSessionResponse("W1", "m1", SCHEDULING_URL, TeamsSamples.read("meetings.json").toString());
        JsonNode json = call(tools, TeamsTools.FIND_MEETINGS, wideWindow());

        assertEquals(1, json.path("meetings").size(), json.toString());
        assertEquals("Comité de migration", json.path("meetings").get(0).path("subject").asText());
        assertFalse(json.has("viewport"), "le registre n'était pas vide : aucune navigation");
        assertTrue(teams.browser.navigations().isEmpty());
        assertEquals(1, teams.browser.sentCommands().stream().filter(CdpCommands.SET_AUTO_ATTACH::equals).count(),
                "une seule demande d'auto-attach par liaison");
    }

    @Test
    @DisplayName("(a) un cadre hors domaines Microsoft n'est jamais attaché ni compté")
    void a_foreign_frame_is_never_counted() throws Exception {
        PaperTeams teams = new PaperTeams();
        TeamsTools tools = teams.tools();
        call(tools, TeamsTools.STATUS, ask());

        teams.browser.emitAttached("AD", "iframe", "https://ads.example.com/frame");
        teams.browser.emitSessionResponse("AD", "x1", "https://teams.microsoft.com/api/chatsvc/emea/v1/users/ME/conversations",
                TeamsSamples.read("conversation-list.json").toString());
        JsonNode status = call(tools, TeamsTools.STATUS, ask());

        JsonNode observation = status.path("diagnostic").path("observation");
        assertTrue(observation.path("framesObserved").asBoolean());
        assertFalse(observation.path("responsesByOrigin").has("FRAME"), observation.toString());
        assertFalse(teams.browser.sessionCommands().stream().anyMatch(command -> command.startsWith("AD|")));
        teams.browser.sentCommands().forEach(method -> assertTrue(CdpCommands.isAllowed(method), method));
    }

    // ------------------------------------------------------------------ (b) le diagnostic chiffré

    @Test
    @DisplayName("(b) teams_status porte le diagnostic : origines, natures, non classés gabarisés et triés, sockets")
    void status_carries_the_observation_diagnostic() throws Exception {
        PaperTeams teams = new PaperTeams();
        TeamsTools tools = teams.tools();
        call(tools, TeamsTools.STATUS, ask());

        teams.browser.emitAttached("SW", "service_worker", "https://teams.microsoft.com/v2/sw.js");
        for (int i = 0; i < 3; i++) {
            teams.browser.emitSessionResponse("SW", "u" + i,
                    "https://contoso-my.sharepoint.com/personal/jean_contoso_com/_api/SP.Web.GetContextWebThemeData?t=SECRET", "{}");
        }
        teams.browser.emitResponse("h1", "https://teams.microsoft.com/api/chatsvc/fr/v1/threads/19:secretthread@thread.v2/"
                + "consumptionhorizons?token=SECRET", "{}");
        teams.browser.emitResponse("c1", TeamsSamples.CONVERSATIONS_URL, TeamsSamples.read("conversation-list.json").toString());
        teams.browser.emitResponse("e1", "https://cdn.example.net/api/thing", "{}");
        teams.browser.emitSocket("", "ws1", "wss://pubsub.trouter.teams.microsoft.com/v4/c?epid=SECRET");
        teams.browser.emitSocketFrame("", "ws1", "SECRET-MESSAGE-DANS-LA-TRAME");
        teams.browser.emitSocketFrame("", "ws1", "SECRET-MESSAGE-DANS-LA-TRAME");

        String rendered = tools.execute(TeamsTools.STATUS, ask()).content();
        JsonNode observation = mapper.readTree(rendered).path("diagnostic").path("observation");

        assertEquals(3, observation.path("responsesByOrigin").path("SERVICE_WORKER").asInt(), observation.toString());
        assertTrue(observation.path("responsesByOrigin").path("TEAMS_TAB").asInt() >= 3);
        assertEquals(1, observation.path("classifiedByKind").path("CONVERSATION_LIST").asInt());
        assertEquals(4, observation.path("unknownMicrosoft").asInt());
        assertEquals(1, observation.path("unknownElsewhere").asInt());
        JsonNode top = observation.path("topUnknownPaths");
        assertEquals("*-my.sharepoint.com/personal/{id}/_api/SP.Web.GetContextWebThemeData", top.get(0).path("path").asText());
        assertEquals(3, top.get(0).path("count").asInt());
        assertEquals("teams.microsoft.com/api/chatsvc/fr/v1/threads/{id}/consumptionhorizons", top.get(1).path("path").asText());
        assertEquals(1, observation.path("sockets").asInt());
        assertEquals(2, observation.path("socketFrames").asInt());
        assertEquals(1, observation.path("attachedByOrigin").path("SERVICE_WORKER").asInt());
        for (String secret : List.of("contoso", "jean_", "secretthread", "SECRET", "epid", "?")) {
            assertFalse(observation.toString().contains(secret), secret + " : " + observation);
        }
    }

    @Test
    @DisplayName("(b) zéro + trafic Microsoft non classé : NOTHING_CLASSIFIED — « n'a pas été reconnu », cliquer n'y changera rien")
    void unrecognized_traffic_is_said_and_the_survey_is_proposed() throws Exception {
        PaperTeams teams = new PaperTeams();
        TeamsTools tools = teams.tools();
        // Deux réponses Microsoft NON classées et NON bruitées (le pubsub d'abord utilisé ici est
        // désormais reconnu comme bruit — SF-89-10 — et ne compte plus comme trafic à apprendre).
        teams.browser.emitResponse("h1", "https://teams.microsoft.com/api/mcps/eu/contents", "{}");
        teams.browser.emitResponse("h2", "https://teams.microsoft.com/api/chatsvc/fr/v1/threads/19:x@thread.v2/consumptionhorizons", "{}");

        String rendered = tools.execute(TeamsTools.FIND_CONVERSATIONS, ask()).content();
        JsonNode json = mapper.readTree(rendered);

        assertEquals(0, json.path("conversations").size());
        JsonNode gap = null;
        for (JsonNode candidate : json.path("gaps")) {
            if ("NOTHING_CLASSIFIED".equals(candidate.path("kind").asText())) {
                gap = candidate;
            }
        }
        assertTrue(gap != null, json.path("gaps").toString());
        assertTrue(gap.path("detail").asText().contains("aucune reconnue par l'adaptateur"), gap.toString());
        String text = json.path("text").asText();
        assertTrue(text.contains("Le contenu est arrivé mais n'a pas été reconnu"), text);
        assertTrue(text.contains("n'y changera rien"), text);
        assertTrue(text.contains("teams_status"), "renvoie à l'inventaire complet : " + text);
        assertFalse(text.contains("--releve-teams"), "le relevé exige un opérateur au clavier (D4) : " + text);
        assertEquals(2, json.path("observation").path("unknownMicrosoft").asInt());
        assertFalse(json.path("text").asText().contains("rien d'observé depuis le rattachement : aucune"),
                "le zéro n'est plus présenté comme « rien d'affiché »");
    }

    @Test
    @DisplayName("(b) aucune réponse du tout : « aucune réponse réseau observée depuis le rattachement »")
    void no_traffic_at_all_is_said() throws Exception {
        PaperTeams teams = new PaperTeams();

        JsonNode json = call(teams.tools(), TeamsTools.FIND_MEETINGS, wideWindow());

        assertEquals(0, json.path("meetings").size());
        assertTrue(json.path("text").asText().contains("Aucune réponse réseau observée depuis le rattachement"),
                json.path("text").asText());
        assertEquals(0, json.path("observation").path("responses").asInt());
    }

    @Test
    @DisplayName("(b) un fil sans rien : on ne prétend pas que le réseau garantit la lecture d'un fil en cache")
    void an_empty_thread_does_not_pretend() throws Exception {
        PaperTeams teams = new PaperTeams();

        JsonNode json = call(teams.tools(), TeamsTools.READ_CONVERSATION,
                ask().put("conversation_id", PaperTeams.THREAD));

        assertEquals(0, json.path("messages").size());
        assertTrue(json.path("text").asText().contains(TeamsTools.CACHED_THREAD_NOTE), json.path("text").asText());
        assertTrue(json.has("observation"));
    }

    // ------------------------------------------------------------------ (c) provoquer le chargement

    @Test
    @DisplayName("(c) registre vide : la liste des conversations est ouverte, lue, la vue remise, et c'est dit")
    void an_empty_ledger_loads_the_conversation_list() throws Exception {
        PaperTeams teams = new PaperTeams();
        String before = teams.browser.route();
        teams.browser.deliverOnNavigate("#/conversations", "", "c1", TeamsSamples.CONVERSATIONS_URL,
                TeamsSamples.read("conversation-list.json").toString());

        JsonNode json = call(teams.tools(), TeamsTools.FIND_CONVERSATIONS, ask());

        assertTrue(json.path("conversations").size() > 0, json.toString());
        assertEquals("https://teams.microsoft.com/v2/#/conversations", teams.browser.navigations().get(0));
        assertEquals(before, teams.browser.route(), "la vue de l'utilisateur est remise");
        assertTrue(json.path("viewport").asText().contains("j'ai ouvert la liste des conversations"),
                json.path("viewport").asText());
        assertTrue(json.path("viewport").asText().contains("remis la vue"));
    }

    @Test
    @DisplayName("(c) registre vide : le calendrier est ouvert pour les réunions, sur l'hôte Teams de l'onglet")
    void an_empty_ledger_loads_the_calendar_on_the_tab_host() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.showingUrl("https://teams.cloud.microsoft/v2/");
        TeamsTools tools = teams.tools();
        call(tools, TeamsTools.STATUS, ask());
        teams.browser.emitAttached("W1", "worker", WORKER_URL);
        teams.browser.deliverOnNavigate("calendarv2", "W1", "m1", SCHEDULING_URL,
                TeamsSamples.read("meetings.json").toString());

        JsonNode json = call(tools, TeamsTools.FIND_MEETINGS, wideWindow());

        assertEquals(1, json.path("meetings").size(), json.toString());
        assertEquals("https://teams.cloud.microsoft/v2/#/calendarv2", teams.browser.navigations().get(0));
        assertEquals("https://teams.cloud.microsoft/v2/", teams.browser.route());
        assertTrue(json.path("viewport").asText().contains("le calendrier"));
    }

    @Test
    @DisplayName("(c) registre non vide sans correspondance : aucune navigation")
    void a_non_empty_ledger_never_navigates() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("c1", TeamsSamples.CONVERSATIONS_URL, "conversation-list.json");

        JsonNode json = call(teams.tools(), TeamsTools.FIND_CONVERSATIONS, ask().put("query", "Personne-Inexistante"));

        assertEquals(0, json.path("conversations").size());
        assertTrue(teams.browser.navigations().isEmpty());
        assertFalse(json.has("viewport"));
    }

    @Test
    @DisplayName("(c) onglet sur une page d'identification : rien ne part, et le résultat le dit")
    void a_sign_in_tab_is_never_navigated() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.showingUrl("https://login.microsoftonline.com/common/oauth2");

        JsonNode json = call(teams.tools(), TeamsTools.FIND_CONVERSATIONS, ask());

        assertEquals(0, json.path("conversations").size());
        assertTrue(teams.browser.navigations().isEmpty());
        assertTrue(json.path("viewport").asText().contains("n'est pas sur Teams"), json.toString());
    }

    @Test
    @DisplayName("(c) une navigation qui atterrit sur l'identification le dit, sans rien récolter ni cliquer")
    void a_redirect_to_sign_in_is_said() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.redirectingTo("https://login.microsoftonline.com/common/oauth2");

        JsonNode json = call(teams.tools(), TeamsTools.FIND_CONVERSATIONS, ask());

        assertEquals(1, teams.browser.navigations().size());
        assertTrue(json.path("viewport").asText().contains("page d'identification"), json.toString());
    }

    // ------------------------------------------------------------------ l'adaptateur et le relevé

    @Test
    @DisplayName("Relevé réel : l'événement de calendrier est lu comme une réunion ; l'objet de collaboration est lu SANS qu'un champ inconnu ne fuie (SF-89-13)")
    void calendar_event_and_collab_object_are_both_read() throws Exception {
        assertEquals(TeamsPayloadKind.CALENDAR_EVENT, TeamsUrls.classify(CALENDAR_EVENT_URL));
        assertEquals(TeamsPayloadKind.MEETING_COLLAB_OBJECT, TeamsUrls.classify(COLLAB_URL));
        assertEquals(TeamsPayloadKind.MEETING_DETAILS, TeamsUrls.classify(SCHEDULING_URL));

        PaperTeams teams = new PaperTeams();
        TeamsTools tools = teams.tools();
        call(tools, TeamsTools.STATUS, ask());
        teams.browser.emitResponse("e1", CALENDAR_EVENT_URL, TeamsSamples.read("calendar-event.json").toString());
        int bodiesBefore = (int) teams.browser.sentCommands().stream().filter(CdpCommands.GET_RESPONSE_BODY::equals).count();
        // F-89 / SF-89-13 : l'objet de collaboration est désormais lu. Son corps porte un champ inconnu
        // « recap » ; comme l'adaptateur ne lit que des champs NOMMÉS (resources[].metadata.*), ce champ
        // ne franchit jamais la couche — la garantie « rien recopié en aveugle » tient.
        teams.browser.emitResponse("k1", COLLAB_URL, "{\"recap\":\"SECRET-RECAP\"}");
        JsonNode json = call(tools, TeamsTools.FIND_MEETINGS, wideWindow());

        assertEquals(1, json.path("meetings").size(), json.toString());
        JsonNode meeting = json.path("meetings").get(0);
        assertEquals("Revue d'architecture IAM", meeting.path("subject").asText());
        assertEquals(Instant.parse("2026-09-12T13:00:00Z").toString(), meeting.path("startedAt").asText());
        assertEquals("19:meeting_revue-iam@thread.v2", meeting.path("conversationId").asText());
        assertEquals(2, meeting.path("participants").size());
        long bodies = teams.browser.sentCommands().stream().filter(CdpCommands.GET_RESPONSE_BODY::equals).count();
        assertEquals(bodiesBefore + 2, bodies, "les DEUX corps sont demandés : l'événement ET l'objet de collaboration");
        assertFalse(json.toString().contains("SECRET-RECAP"), "aucun champ inconnu ne fuit");
    }

    @Test
    @DisplayName("Un horaire d'événement au fuseau Windows n'est pas deviné")
    void a_windows_time_zone_is_not_guessed() throws Exception {
        String body = TeamsSamples.read("calendar-event.json").toString()
                .replace("\"timeZone\":\"UTC\"", "\"timeZone\":\"Romance Standard Time\"");
        TeamsReading<TeamsMeeting> reading = TeamsAdapters.current().meetings(CALENDAR_EVENT_URL, mapper.readTree(body));

        assertTrue(reading.items().isEmpty());
        assertTrue(reading.gaps().stream().anyMatch(gap -> gap.kind() == TeamsGapKind.MISSING_FIELD));
    }

    @Test
    @DisplayName("La famille Microsoft sert au comptage, jamais aux gestes")
    void the_family_counts_but_never_allows() {
        assertTrue(MicrosoftDomains.isMicrosoftFamily("https://augloop.office.com/sessioninit"));
        assertTrue(MicrosoftDomains.isMicrosoftFamily("https://editor.svc.cloud.microsoft/NLEditor/Config/V2"));
        assertTrue(MicrosoftDomains.isMicrosoftFamily("https://statics.teams.cdn.office.net/x"));
        assertTrue(MicrosoftDomains.isMicrosoftFamily("https://x.svc.ms/y"));
        assertFalse(MicrosoftDomains.isMicrosoftFamily("https://login.microsoftonline.com/common"));
        assertFalse(MicrosoftDomains.isMicrosoftFamily("https://login.microsoft.com/common"));
        assertFalse(MicrosoftDomains.isMicrosoftFamily("https://evil-microsoft.com.example.org/"));
        assertFalse(MicrosoftDomains.isAllowed("https://statics.teams.cdn.office.net/x"),
                "F-108 inchangé : la famille n'ouvre aucun geste");
        assertFalse(MicrosoftDomains.isAllowed("https://x.svc.ms/y"));
    }

    @Test
    @DisplayName("Une route est reportée sur l'hôte Teams de l'onglet, jamais sur un autre site")
    void a_route_follows_the_tab_host() {
        assertEquals("https://teams.cloud.microsoft/v2/#/calendarv2",
                TeamsRoutes.onTabHost(TeamsRoutes.CALENDAR, "https://teams.cloud.microsoft/v2/?ring=general"));
        assertEquals(TeamsRoutes.CALENDAR, TeamsRoutes.onTabHost(TeamsRoutes.CALENDAR, "https://contoso.sharepoint.com/x"));
        assertEquals(TeamsRoutes.CALENDAR, TeamsRoutes.onTabHost(TeamsRoutes.CALENDAR, ""));
    }

    @Test
    @DisplayName("Relevé : la famille Microsoft est détaillée, les sockets et leurs trames sont comptées sans être lues")
    void the_survey_details_the_family_and_counts_sockets() {
        SurveyFakeBrowser tab = new SurveyFakeBrowser();
        NetworkSurvey survey = new NetworkSurvey(Runnable::run);
        survey.watchTeamsTab(tab);
        tab.respond("", "https://augloop.office.com/sessioninit?x=SECRET", "XHR", "application/json", 200);
        tab.respond("", "https://login.microsoftonline.com/common/oauth2/v2.0/token", "XHR", "application/json", 200);
        tab.socket("", "ws1", "wss://pubsub.trouter.teams.microsoft.com/v4/c?epid=SECRET");
        tab.frame("", "ws1", "SECRET-TRAME");
        tab.frame("", "ws1", "SECRET-TRAME");
        tab.socket("", "ws2", "wss://relay.example.com/socket");

        NetworkSurvey.Snapshot snapshot = survey.snapshot();
        assertTrue(snapshot.entries().stream().anyMatch(entry -> entry.host().equals("augloop.office.com")));
        assertEquals(2, snapshot.outsideMicrosoft(), "identification et socket étrangère : comptées, non détaillées");
        assertEquals(1, snapshot.sockets().size());
        assertEquals("pubsub.trouter.teams.microsoft.com", snapshot.sockets().get(0).host());
        assertEquals(2, snapshot.sockets().get(0).frames());

        SurveyReport report = new SurveyReport(snapshot, Instant.EPOCH, Instant.EPOCH, false, "Chrome", "v1");
        String markdown = report.markdown();
        assertTrue(markdown.contains("## Sockets WebSocket"));
        assertFalse(markdown.contains("SECRET"), markdown);
        assertFalse(report.json().contains("SECRET"));
    }
}
