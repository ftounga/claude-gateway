package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-100 / SF-100-03 — <b>la collecte Teams de la synchro du soir</b>, sur un Teams de papier : découverte
 * seule, seul le nouveau, canaux limités aux fils de l'utilisateur, fils ignorés écartés, réunions
 * transcrites sans rien écrire, et une couverture qui dit ce qui n'a pas été lu.
 */
class TeamsRadarCollectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYNC = "7f000001-0000-4000-8000-0000000000aa";
    private static final String CHANNEL = PaperTeams.THREAD; // « topic » : un canal d'équipe
    private static final String ONE_ON_ONE = PaperTeams.OTHER_THREAD;
    private static final String MEETING_CHAT = "19:meeting_fabrique@thread.v2";
    private static final Instant NOW = Instant.parse("2026-09-13T20:00:00Z");

    /** Un contexte de papier : note les battements et les lots, répond ce qu'on lui dit. */
    static final class PaperContext implements RadarSyncContext {
        final List<String> phases = new ArrayList<>();
        final List<ObjectNode> submitted = new ArrayList<>();
        Function<ObjectNode, JsonNode> answer = body -> MAPPER.createObjectNode().put("status", "RUNNING");
        boolean failSubmit;
        boolean stopped;

        @Override
        public boolean progress(String phase, int done, int total) {
            phases.add(phase + ":" + done + "/" + total);
            return !stopped;
        }

        @Override
        public JsonNode submit(ObjectNode body) throws IOException {
            if (failSubmit) {
                throw new IOException("gateway injoignable");
            }
            submitted.add(body);
            JsonNode node = answer.apply(body);
            if ("STOPPED".equals(node.path("status").asText())) {
                stopped = true;
            }
            return node;
        }

        @Override
        public boolean stopped() {
            return stopped;
        }
    }

    private static RadarAssignment assignment(String windowFrom, ObjectNode extra) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("sync_id", SYNC);
        input.put("trigger", "SCHEDULED");
        input.put("first_sync", false);
        input.put("window_from", windowFrom);
        if (extra != null) {
            input.setAll(extra);
        }
        return RadarAssignment.from(input);
    }

    private static ObjectNode refs(String field, String... values) {
        ObjectNode node = MAPPER.createObjectNode();
        ArrayNode array = node.putArray(field);
        for (String value : values) {
            array.add(value);
        }
        return node;
    }

    /** Un Teams de papier qui a déjà servi le profil, la liste des conversations et le canal. */
    private static PaperTeams teams() {
        return new PaperTeams()
                .already("p", "https://teams.microsoft.com/api/mt/emea/beta/users/" + TeamsSamples.SELF + "/profile",
                        "profile.json")
                .already("c", TeamsSamples.CONVERSATIONS_URL, "conversation-list.json")
                .already("m", PaperTeams.MESSAGES_URL, "conversation-messages.json");
    }

    private static TeamsRadarCollector collector(PaperTeams teams, List<PageActions.GestureRecord> gestures) {
        return collector(teams, gestures, millis -> { });
    }

    private static TeamsRadarCollector collector(PaperTeams teams, List<PageActions.GestureRecord> gestures,
            BrowserLink.Sleeper sleeper) {
        TeamsLedger ledger = teams.ledger();
        TeamsSession session = new TeamsSession(9222, teams.adapter, line -> { }, (port, adapter, say) -> teams.link);
        return new TeamsRadarCollector(session, () -> ledger, sleeper, () -> NOW,
                gestures == null ? record -> { } : gestures::add, new Object());
    }

    private static JsonNode firstExchange(ObjectNode body) {
        return body.path("batch").path("exchanges").get(0);
    }

    @Test
    @DisplayName("Seul le nouveau d'un canal à lire en entier ; fils ignorés et sans nouveauté écartés ; SUCCEEDED")
    void readsOnlyWhatIsNew() {
        PaperTeams teams = teams();
        PaperContext context = new PaperContext();
        ObjectNode extra = refs("ignored", ONE_ON_ONE);
        extra.setAll(refs("read_channels", CHANNEL));

        RadarCollector.Outcome outcome = collector(teams, null)
                .collect(assignment("2026-09-10T12:00:00Z", extra), context);

        assertEquals("SUCCEEDED", outcome.status(), outcome.coverage().toString());
        assertEquals(1, context.submitted.size());
        ObjectNode body = context.submitted.get(0);
        JsonNode exchange = firstExchange(body);
        assertEquals("TEAMS_MESSAGE", exchange.path("source").asText());
        assertEquals(CHANNEL, exchange.path("conversationRef").asText());
        assertEquals("Migration MFA", exchange.path("title").asText());
        assertEquals(1, exchange.path("messages").size(), "seul le message postérieur au plancher remonte");
        JsonNode message = exchange.path("messages").get(0);
        assertEquals(CHANNEL + "/1757748000000", message.path("sourceRef").asText());
        assertEquals("Le comité est décalé au T3.", message.path("text").asText());
        assertFalse(message.path("fromMe").asBoolean());
        assertTrue(body.path("batch").path("batchKey").asText().startsWith("teams:"));
        JsonNode cursor = body.path("cursors").get(0);
        assertEquals(CHANNEL, cursor.path("ref").asText());
        assertEquals("2026-09-12T07:05:10Z", cursor.path("at").asText());

        JsonNode coverage = outcome.coverage();
        assertEquals(1, coverage.path("conversations").path("ignored").asInt());
        assertEquals(1, coverage.path("conversations").path("read").asInt());
        assertTrue(coverage.path("discovery").path("complete").asBoolean());
        assertEquals(1, coverage.path("batches").asInt());
        assertTrue(context.phases.contains("conversations:0/1"));
    }

    @Test
    @DisplayName("Rejouer la même collecte redonne les mêmes clés de lot (idempotence)")
    void batchKeysAreStable() {
        ObjectNode extra = refs("ignored", ONE_ON_ONE);
        extra.setAll(refs("read_channels", CHANNEL));
        PaperContext first = new PaperContext();
        collector(teams(), null).collect(assignment("2026-09-10T12:00:00Z", extra), first);
        PaperContext second = new PaperContext();
        collector(teams(), null).collect(assignment("2026-09-10T12:00:00Z", extra), second);
        assertEquals(first.submitted.get(0).path("batch").path("batchKey"),
                second.submitted.get(0).path("batch").path("batchKey"));
    }

    @Test
    @DisplayName("Canal d'équipe : seuls les fils où l'utilisateur est mentionné ou a répondu ; la liste coupée rend PARTIAL")
    void channelLimitedToUserThreads() {
        PaperContext context = new PaperContext();
        RadarCollector.Outcome outcome = collector(teams(), null)
                .collect(assignment("2026-09-01T00:00:00Z", refs("ignored", ONE_ON_ONE, MEETING_CHAT)), context);

        assertEquals(1, context.submitted.size());
        JsonNode messages = firstExchange(context.submitted.get(0)).path("messages");
        assertEquals(2, messages.size(), "le fil mentionné et la réponse de l'utilisateur, pas le message de Claire");
        assertEquals(CHANNEL + "/1757662323000", messages.get(0).path("sourceRef").asText());
        assertTrue(messages.get(1).path("fromMe").asBoolean(), "la réponse est de l'utilisateur relié");
        assertEquals("PARTIAL", outcome.status(), "toutes les conversations listées sont récentes : la liste a pu être coupée");
        assertFalse(outcome.coverage().path("discovery").path("complete").asBoolean());
    }

    @Test
    @DisplayName("Canal actif sans fil de l'utilisateur : compté « non lu », rien ne remonte")
    void unreadChannelIsCounted() {
        PaperTeams teams = new PaperTeams()
                .already("c", TeamsSamples.CONVERSATIONS_URL, "conversation-list.json");
        PaperContext context = new PaperContext();

        RadarCollector.Outcome outcome = collector(teams, null)
                .collect(assignment("2026-09-11T00:00:00Z", refs("ignored", ONE_ON_ONE)), context);

        assertTrue(context.submitted.isEmpty());
        JsonNode coverage = outcome.coverage();
        assertEquals(1, coverage.path("channels").path("unreadActive").asInt());
        JsonNode listed = coverage.path("threads").get(0);
        assertEquals("UNREAD_CHANNEL", listed.path("status").asText());
        assertEquals("Migration MFA", listed.path("label").asText());
    }

    @Test
    @DisplayName("Curseur respecté : un fil sans activité depuis son curseur n'est pas relu")
    void cursorIsRespected() {
        ObjectNode extra = refs("ignored", ONE_ON_ONE);
        extra.setAll(refs("read_channels", CHANNEL));
        ArrayNode cursors = extra.putArray("cursors");
        cursors.addObject().put("ref", CHANNEL).put("at", "2026-09-12T08:00:00Z");
        PaperContext context = new PaperContext();

        RadarCollector.Outcome outcome = collector(teams(), null).collect(assignment("2026-09-10T12:00:00Z", extra), context);

        assertTrue(context.submitted.isEmpty());
        assertEquals(0, outcome.coverage().path("conversations").path("read").asInt());
    }

    @Test
    @DisplayName("Lot qui ne remonte pas : fil FAILED, aucun lot compté, issue FAILED nommée")
    void submitFailure() {
        ObjectNode extra = refs("ignored", ONE_ON_ONE);
        extra.setAll(refs("read_channels", CHANNEL));
        PaperContext context = new PaperContext();
        context.failSubmit = true;

        RadarCollector.Outcome outcome = collector(teams(), null).collect(assignment("2026-09-10T12:00:00Z", extra), context);

        assertEquals("FAILED", outcome.status());
        assertEquals("NOTHING_UPLOADED", outcome.coverage().path("failure").path("code").asText());
        assertEquals(1, outcome.coverage().path("conversations").path("failed").asInt());
    }

    @Test
    @DisplayName("Synchro close côté gateway : la collecte s'arrête, rien n'est navigué")
    void stopsWhenClosed() {
        ObjectNode extra = refs("ignored", ONE_ON_ONE);
        extra.setAll(refs("read_channels", CHANNEL));
        PaperContext context = new PaperContext();
        context.answer = body -> MAPPER.createObjectNode().put("status", "STOPPED");
        List<PageActions.GestureRecord> gestures = new ArrayList<>();

        collector(teams(), gestures).collect(assignment("2026-09-10T12:00:00Z", extra), context);

        assertTrue(gestures.isEmpty(), "aucun geste après l'arrêt");
        assertFalse(context.phases.stream().anyMatch(phase -> phase.startsWith("meetings")));
    }

    @Test
    @DisplayName("Session Microsoft expirée : FAILED SESSION_EXPIRED avec le geste à faire")
    void sessionExpired() {
        TeamsSession session = new TeamsSession(9222, TeamsAdapters.current(), line -> { }, (port, adapter, say) -> {
            throw new BrowserLinkException(BrowserLinkException.NOT_SIGNED_IN, "Reconnectez-vous à Teams.");
        });
        TeamsRadarCollector collector = new TeamsRadarCollector(session, () -> new TeamsLedger(TeamsAdapters.current()),
                millis -> { }, () -> NOW, record -> { }, new Object());

        RadarCollector.Outcome outcome = collector.collect(assignment("2026-09-10T12:00:00Z", null), new PaperContext());

        assertEquals("FAILED", outcome.status());
        JsonNode failure = outcome.coverage().path("failure");
        assertEquals("SESSION_EXPIRED", failure.path("code").asText());
        assertTrue(failure.path("sentence").asText().contains("reconnectez-vous"));
    }

    @Test
    @DisplayName("Réunion transcrite : échange TEAMS_MEETING ; navigation vers le calendrier et vue remise, jamais un lien de participation")
    void meetingTranscript() {
        PaperTeams teams = teams()
                .already("mt", TeamsSamples.MEETINGS_URL, "meetings.json")
                .already("tr", TeamsSamples.TRANSCRIPT_URL, "transcript.json");
        List<PageActions.GestureRecord> gestures = new ArrayList<>();
        PaperContext context = new PaperContext();
        ObjectNode extra = refs("ignored", ONE_ON_ONE, MEETING_CHAT, CHANNEL);

        RadarCollector.Outcome outcome = collector(teams, gestures).collect(assignment("2026-09-01T00:00:00Z", extra), context);

        JsonNode meetingBatch = context.submitted.stream()
                .filter(body -> "TEAMS_MEETING".equals(firstExchange(body).path("source").asText()))
                .findFirst().orElseThrow(() -> new AssertionError(outcome.coverage().toString()));
        JsonNode exchange = firstExchange((ObjectNode) meetingBatch);
        assertEquals("MTG-FABRIQUE-0001", exchange.path("conversationRef").asText());
        assertEquals("Comité de migration", exchange.path("title").asText());
        assertTrue(exchange.path("messages").size() >= 2);
        assertEquals("2026-09-10T09:00:05.120Z", exchange.path("messages").get(0).path("occurredAt").asText());
        assertTrue(meetingBatch.path("batch").path("batchKey").asText().startsWith("meeting:"));
        assertEquals("MEETING", meetingBatch.path("cursors").get(0).path("kind").asText());
        assertEquals(1, outcome.coverage().path("meetings").path("transcribed").asInt());
        assertEquals("OK", outcome.coverage().path("meetings").path("navigation").asText());

        assertTrue(gestures.size() >= 2, "navigation vers le calendrier, puis vue remise");
        assertEquals(TeamsRoutes.CALENDAR, gestures.get(0).target());
        assertTrue(gestures.stream().allMatch(g -> "navigate".equals(g.action())), "aucun clic, aucune saisie");
        assertTrue(gestures.stream().noneMatch(g -> g.target().contains("meetup-join")), "jamais un lien de participation");
    }

    @Test
    @DisplayName("Réunion sans transcription : NO_TRANSCRIPT ; transcription refusée : DENIED")
    void meetingWithoutTranscript() {
        String meetings = "{\"value\":[{\"id\":\"MTG-A\",\"subject\":\"Point hebdo\",\"startTime\":\"2026-09-11T09:00:00Z\","
                + "\"endTime\":\"2026-09-11T09:30:00Z\",\"threadId\":\"19:meeting_a@thread.v2\",\"isTranscriptAvailable\":false},"
                + "{\"id\":\"MTG-B\",\"subject\":\"Revue sécurité\",\"startTime\":\"2026-09-12T09:00:00Z\","
                + "\"endTime\":\"2026-09-12T10:00:00Z\",\"threadId\":\"19:meeting_b@thread.v2\",\"isTranscriptAvailable\":true}]}";
        PaperTeams teams = new PaperTeams();
        teams.browser.emitResponse("mt", TeamsSamples.MEETINGS_URL, meetings);
        teams.browser.reachable("19:meeting_b@thread.v2");
        PaperContext context = new PaperContext();
        List<PageActions.GestureRecord> gestures = new ArrayList<>();
        TeamsRadarCollector collector = collector(teams, gestures);
        // Deux coups de coude avant (découverte, calendrier) ; puis la réunion B : son fil s'ouvre, et la
        // transcription est refusée par Microsoft.
        teams.browser.deliverOnScroll("x0", "https://teams.microsoft.com/api/chatsvc/emea/v1/users/ME/conversations", "{}");
        teams.browser.deliverOnScroll("x1", "https://teams.microsoft.com/api/chatsvc/emea/v1/users/ME/conversations", "{}");
        teams.browser.deliverOnScroll("deny", "https://contoso.sharepoint.com/sites/x/_api/v2.1/drives/b!1/items/2/media/transcripts",
                "{\"error\":\"forbidden\"}", 403);

        RadarCollector.Outcome outcome = collector.collect(assignment("2026-09-01T00:00:00Z", null), context);

        JsonNode meetingsCoverage = outcome.coverage().path("meetings");
        assertEquals(2, meetingsCoverage.path("seen").asInt(), outcome.coverage().toString());
        assertEquals(1, meetingsCoverage.path("noTranscript").asInt(), outcome.coverage().toString());
        assertEquals(1, meetingsCoverage.path("denied").asInt(), outcome.coverage().toString());
        assertTrue(context.submitted.stream().noneMatch(body -> "TEAMS_MEETING".equals(firstExchange(body).path("source").asText())));
    }

    @Test
    @DisplayName("Découpage : 200 messages par échange, 8 000 caractères par message, clé ≤ 128 et stable")
    void exchangesAreBounded() {
        List<RadarExchanges.Line> lines = new ArrayList<>();
        for (int index = 0; index < 450; index++) {
            lines.add(new RadarExchanges.Line("fil/" + index, NOW.plusSeconds(index), "a@b.c", "A", false,
                    index == 0 ? "x".repeat(9_000) : "message " + index, null));
        }
        List<RadarExchanges.Chunk> chunks = RadarExchanges.chunks(MAPPER, "TEAMS_MESSAGE", "fil", "Titre", null, lines);
        assertEquals(3, chunks.size());
        assertEquals(200, chunks.get(0).messages());
        assertEquals(50, chunks.get(2).messages());
        assertEquals(8_000, chunks.get(0).batch().path("exchanges").get(0).path("messages").get(0).path("text").asText().length());
        assertTrue(chunks.get(0).batchKey().length() <= 128);
        assertEquals(NOW.plusSeconds(199), chunks.get(0).newest());

        List<RadarExchanges.Line> big = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            big.add(new RadarExchanges.Line("f/" + index, NOW.plusSeconds(index), null, null, false, "y".repeat(8_000), null));
        }
        List<RadarExchanges.Chunk> byChars = RadarExchanges.chunks(MAPPER, "TEAMS_MESSAGE", "f", null, null, big);
        assertTrue(byChars.size() >= 3, "350 000 caractères par lot au plus");
        assertEquals(RadarExchanges.chunks(MAPPER, "TEAMS_MESSAGE", "f", null, null, big).get(1).batchKey(),
                byChars.get(1).batchKey());
    }

    @Test
    @DisplayName("Motif SharePoint : transcription reconnue quel que soit le tenant ; enregistrement ignoré")
    void sharePointMotif() {
        assertEquals(TeamsPayloadKind.MEETING_TRANSCRIPT, TeamsUrls.classify(
                "https://contoso.sharepoint.com/sites/Projet/_api/v2.1/drives/b!x/items/01AB/media/transcripts"));
        assertEquals(TeamsPayloadKind.MEETING_TRANSCRIPT, TeamsUrls.classify(
                "https://fabrikam-my.sharepoint.com/personal/jean/_api/v2.1/drives/b!y/items/02/media/transcripts/3/content"));
        assertEquals(TeamsPayloadKind.UNKNOWN, TeamsUrls.classify(
                "https://contoso.sharepoint.com/sites/Projet/_layouts/15/stream.aspx"));
        assertEquals(TeamsPayloadKind.UNKNOWN, TeamsUrls.classify("https://sharepoint.com/transcripts"));
    }

    // ------------------------------------------------------------------ SF-100-10 : la vue Chat avant de lire

    @Test
    @DisplayName("SF-100-10 : réseau vide → la découverte navigue vers la vue Chat et ATTEND la liste avant de lire")
    void discoveryNavigatesToChatViewAndWaitsForTheList() {
        PaperTeams teams = new PaperTeams(); // le réseau ne sert aucune liste
        // La liste « mid-nav » v2 ne « charge » qu'après deux sondages de présence : la collecte doit attendre.
        teams.browser.listPresentAfter(2, PaperScreen.of("conversations-v2.html"));
        List<PageActions.GestureRecord> gestures = new ArrayList<>();
        int[] polls = { 0 };
        BrowserLink.Sleeper sleeper = millis -> polls[0]++;

        // Fenêtre postérieure à l'activité des fils : la découverte LISTE sans avoir à lire (le sujet ici,
        // c'est le contexte de navigation, pas la lecture — prouvée par ailleurs par SF-89-20/21).
        RadarCollector.Outcome outcome = collector(teams, gestures, sleeper)
                .collect(assignment("2026-09-30T00:00:00Z", null), new PaperContext());

        JsonNode discovery = outcome.coverage().path("discovery");
        assertTrue(discovery.path("served").asBoolean(), outcome.coverage().toString());
        assertEquals(TeamsTools.SOURCE_SCREEN, discovery.path("source").asText(), outcome.coverage().toString());
        assertEquals(1, discovery.path("listed").asInt(), "le treeitem porteur d'un id de fil (SF-89-21)");
        assertTrue(teams.browser.navigations().contains(TeamsRoutes.CHAT), teams.browser.navigations().toString());
        assertTrue(polls[0] >= 2, "la collecte a attendu que la liste charge (poll borné) : " + polls[0]);
        PageActions.GestureRecord diag = gestures.stream()
                .filter(g -> "radar/chat_view".equals(g.action())).findFirst()
                .orElseThrow(() -> new AssertionError("diag F-132 radar/chat_view attendu : " + gestures));
        assertTrue(diag.result().contains("reached"), diag.result());
        assertTrue(diag.result().contains("listed=1"), diag.result());
    }

    @Test
    @DisplayName("SF-100-10 : liste jamais chargée → best-effort borné, listed=0, la couverture le dit, aucun crash")
    void discoveryIsBestEffortWhenTheChatListNeverLoads() {
        PaperTeams teams = new PaperTeams(); // ni réseau, ni écran : la liste ne charge jamais
        List<PageActions.GestureRecord> gestures = new ArrayList<>();
        int[] polls = { 0 };
        BrowserLink.Sleeper sleeper = millis -> polls[0]++;

        RadarCollector.Outcome outcome = collector(teams, gestures, sleeper)
                .collect(assignment("2026-09-01T00:00:00Z", null), new PaperContext());

        JsonNode discovery = outcome.coverage().path("discovery");
        assertFalse(discovery.path("served").asBoolean());
        assertEquals(0, discovery.path("listed").asInt());
        assertEquals("aucune", discovery.path("source").asText());
        assertEquals("PARTIAL", outcome.status(), "best-effort : partielle, jamais un crash ni une réussite mensongère");
        assertTrue(teams.browser.navigations().contains(TeamsRoutes.CHAT));
        assertTrue(polls[0] >= TeamsScreenFallback.MAX_CHAT_LIST_POLLS, "poll borné et épuisé : " + polls[0]);
        PageActions.GestureRecord diag = gestures.stream()
                .filter(g -> "radar/chat_view".equals(g.action())).findFirst()
                .orElseThrow(() -> new AssertionError("diag F-132 radar/chat_view attendu : " + gestures));
        assertTrue(diag.result().contains("timeout"), diag.result());
        assertTrue(diag.result().contains("listed=0"), diag.result());
    }

    @Test
    @DisplayName("SF-100-10 : liste déjà présente (déjà sur la vue Chat) → lecture immédiate, aucune navigation superflue")
    void discoveryReadsImmediatelyWhenAlreadyOnTheChatView() {
        PaperTeams teams = new PaperTeams();
        teams.browser.screen("", PaperScreen.of("conversations-v2.html")); // la liste est déjà affichée
        List<PageActions.GestureRecord> gestures = new ArrayList<>();

        RadarCollector.Outcome outcome = collector(teams, gestures)
                .collect(assignment("2026-09-30T00:00:00Z", null), new PaperContext());

        JsonNode discovery = outcome.coverage().path("discovery");
        assertEquals(TeamsTools.SOURCE_SCREEN, discovery.path("source").asText(), outcome.coverage().toString());
        assertEquals(1, discovery.path("listed").asInt());
        assertFalse(teams.browser.navigations().contains(TeamsRoutes.CHAT),
                "déjà sur la vue Chat : pas de navigation vers la vue Chat");
        PageActions.GestureRecord diag = gestures.stream()
                .filter(g -> "radar/chat_view".equals(g.action())).findFirst().orElseThrow();
        assertTrue(diag.result().contains("reached"), diag.result());
    }

    @Test
    @DisplayName("Cadre Microsoft : réseau écouté et corps demandé sur sa session ; cadre étranger : rien")
    void frameSessionsAreObserved() {
        FakeCdpConnection browser = new FakeCdpConnection();
        NetworkObserver observer = new NetworkObserver(browser, TeamsAdapters.current(), Runnable::run);
        observer.start();
        observer.observeFrames();
        observer.observeFrames(); // une seule fois

        browser.emitAttached("S-STREAM", "iframe", "https://contoso.sharepoint.com/_layouts/15/embed.aspx");
        browser.emitAttached("S-AD", "iframe", "https://ads.example.com/frame");
        browser.emitSessionResponse("S-STREAM", "7", TeamsSamples.TRANSCRIPT_URL,
                TeamsSamples.read("transcript.json").toString());
        browser.emitSessionResponse("S-AD", "8", TeamsSamples.TRANSCRIPT_URL, "{}");

        List<ObservedResponse> observed = observer.collect();
        assertEquals(1, observed.size());
        assertTrue(observed.get(0).hasBody());
        // F-89 / SF-89-08 : l'auto-attach est redemandé sur la session du cadre retenu (ses propres workers),
        // jamais sur celle du cadre étranger.
        assertEquals(List.of("S-STREAM|Network.enable", "S-STREAM|Target.setAutoAttach",
                "S-STREAM|Network.getResponseBody"), browser.sessionCommands());
        assertEquals(1, browser.sentCommands().stream().filter(CdpCommands.SET_AUTO_ATTACH::equals).count());
    }
}
