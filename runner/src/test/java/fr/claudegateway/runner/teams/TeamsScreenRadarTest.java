package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.ToolContext;
import fr.claudegateway.runner.ToolOutcome;

/**
 * F-89 / SF-89-06 — <b>le repli sur l'écran, pour le Radar</b> : la vérification guidée coche ses cases
 * quand la lecture vient de l'écran (et le dit), et la synchro du soir lit à l'écran les fils servis depuis
 * le cache et les transcriptions — une transcription au téléchargement bloqué remonte dans un lot qui le
 * <b>dit</b>, pour que la gateway n'en garde jamais que des extraits.
 */
class TeamsScreenRadarTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-13T20:00:00Z");
    private static final String MEETING_CHAT = "19:meeting_fabrique@thread.v2";

    private static JsonNode verify(TeamsTools tools) throws Exception {
        ToolOutcome outcome = tools.execute(RadarTools.VERIFY, MAPPER.createObjectNode(), ToolContext.none());
        assertTrue(outcome.ok(), outcome.content());
        return MAPPER.readTree(outcome.content());
    }

    /** Un contexte de synchro de papier : il garde les lots. */
    private static final class Context implements RadarSyncContext {
        final List<ObjectNode> submitted = new ArrayList<>();

        @Override
        public boolean progress(String phase, int done, int total) {
            return true;
        }

        @Override
        public JsonNode submit(ObjectNode body) throws IOException {
            submitted.add(body);
            return MAPPER.createObjectNode().put("status", "RUNNING");
        }

        @Override
        public boolean stopped() {
            return false;
        }
    }

    private static TeamsRadarCollector collector(PaperTeams teams) {
        TeamsLedger ledger = teams.ledger();
        TeamsSession session = new TeamsSession(9222, teams.adapter, line -> { }, (port, adapter, say) -> teams.link);
        return new TeamsRadarCollector(session, () -> ledger, millis -> { }, () -> NOW, record -> { }, new Object());
    }

    private static RadarAssignment assignment(String windowFrom, ObjectNode extra) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("sync_id", "7f000001-0000-4000-8000-0000000000bb");
        input.put("trigger", "SCHEDULED");
        input.put("first_sync", false);
        input.put("window_from", windowFrom);
        if (extra != null) {
            input.setAll(extra);
        }
        return RadarAssignment.from(input);
    }

    // ------------------------------------------------------------------ vérification guidée

    @Test
    @DisplayName("Vérification : la case conversations se coche sur la liste affichée, et dit « à l'écran » — sans nom")
    void verify_counts_the_displayed_conversation_list() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.screen("", PaperScreen.of("conversations.html"));

        JsonNode result = verify(teams.tools());
        JsonNode conversations = result.path("checks").path("conversations");

        assertTrue(conversations.path("ok").asBoolean(), result.toString());
        assertEquals(2, conversations.path("count").asInt());
        assertEquals("ecran", conversations.path("source").asText());
        assertTrue(conversations.path("sentence").asText().contains("à l'écran"));
        for (String forbidden : List.of("Migration IAM", "Paul Durand", "19:")) {
            assertFalse(result.toString().contains(forbidden), "fuite « " + forbidden + " » : " + result);
        }
    }

    @Test
    @DisplayName("Vérification : la case transcriptions se coche sur le panneau affiché, dit la source et le blocage")
    void verify_counts_the_displayed_transcript() throws Exception {
        PaperTeams teams = new PaperTeams().already("m1", TeamsSamples.MEETINGS_URL, "meetings.json");
        teams.browser.screen("", PaperScreen.of("transcription-bloquee-1.html", "transcription-bloquee-2.html"));

        JsonNode transcripts = verify(teams.tools()).path("checks").path("transcripts");

        assertTrue(transcripts.path("ok").asBoolean(), transcripts.toString());
        assertEquals("SEEN", transcripts.path("reason").asText());
        assertEquals(3, transcripts.path("count").asInt());
        assertEquals("ecran", transcripts.path("source").asText());
        assertTrue(transcripts.path("downloadBlocked").asBoolean());
        assertTrue(transcripts.path("sentence").asText().contains("jamais la conserver en entier"),
                transcripts.path("sentence").asText());
        assertFalse(transcripts.toString().contains("MFA"), "aucune réplique dans la vérification");
    }

    @Test
    @DisplayName("Vérification : le réseau a servi — l'écran n'est pas lu, source « reseau »")
    void verify_prefers_the_network() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("c1", TeamsSamples.CONVERSATIONS_URL, "conversation-list.json")
                .already("m1", TeamsSamples.MEETINGS_URL, "meetings.json")
                .already("t1", TeamsSamples.TRANSCRIPT_URL, "transcript.json");
        teams.browser.screen("", PaperScreen.of("conversations.html"));

        JsonNode checks = verify(teams.tools()).path("checks");

        assertEquals("reseau", checks.path("conversations").path("source").asText());
        assertEquals("reseau", checks.path("transcripts").path("source").asText());
        assertTrue(teams.browser.screenScripts().isEmpty());
    }

    // ------------------------------------------------------------------ synchro du soir

    @Test
    @DisplayName("Synchro : un fil servi depuis le cache est lu à l'écran jusqu'au plancher, remonté, et compté")
    void collect_reads_a_cached_thread_on_screen() {
        PaperTeams teams = new PaperTeams()
                .already("p", "https://teams.microsoft.com/api/mt/emea/beta/users/" + TeamsSamples.SELF + "/profile",
                        "profile.json")
                .already("c", TeamsSamples.CONVERSATIONS_URL, "conversation-list.json");
        teams.browser.screen("", PaperScreen.of("fil-ecran-1.html", "fil-ecran-2.html", "fil-ecran-3.html"));
        ObjectNode extra = MAPPER.createObjectNode();
        extra.putArray("ignored").add(PaperTeams.OTHER_THREAD).add(MEETING_CHAT);
        extra.putArray("read_channels").add(PaperTeams.THREAD);
        Context context = new Context();

        RadarCollector.Outcome outcome = collector(teams).collect(assignment("2026-09-10T00:00:00Z", extra), context);

        JsonNode exchange = context.submitted.stream()
                .map(body -> body.path("batch").path("exchanges").get(0))
                .filter(node -> "TEAMS_MESSAGE".equals(node.path("source").asText()))
                .findFirst().orElseThrow(() -> new AssertionError(outcome.coverage().toString()));
        assertEquals(PaperTeams.THREAD, exchange.path("conversationRef").asText());
        assertEquals(3, exchange.path("messages").size(), exchange.toString());
        assertEquals("Le plan de bascule est prêt.", exchange.path("messages").get(0).path("text").asText());
        assertTrue(exchange.path("messages").get(0).path("sourceRef").asText().startsWith(PaperTeams.THREAD + "/ecran:"));
        assertEquals(1, outcome.coverage().path("conversations").path("readOnScreen").asInt());
        assertEquals(1, outcome.coverage().path("conversations").path("read").asInt(), outcome.coverage().toString());
        assertFalse(exchange.toString().contains("SECRET"));
    }

    @Test
    @DisplayName("Synchro : transcription lue à l'écran au téléchargement bloqué — le lot porte downloadBlocked")
    void collect_flags_a_blocked_transcript() {
        PaperTeams teams = new PaperTeams().already("m1", TeamsSamples.MEETINGS_URL, "meetings.json");
        teams.browser.reachable(MEETING_CHAT);
        teams.browser.screen("", PaperScreen.of("transcription-bloquee-1.html", "transcription-bloquee-2.html"));
        Context context = new Context();

        RadarCollector.Outcome outcome = collector(teams).collect(assignment("2026-09-01T00:00:00Z", null), context);

        JsonNode exchange = context.submitted.stream()
                .map(body -> body.path("batch").path("exchanges").get(0))
                .filter(node -> "TEAMS_MEETING".equals(node.path("source").asText()))
                .findFirst().orElseThrow(() -> new AssertionError(outcome.coverage().toString()));
        assertTrue(exchange.path("downloadBlocked").asBoolean(), exchange.toString());
        assertEquals(3, exchange.path("messages").size());
        assertEquals("2026-09-10T09:01:05Z", exchange.path("messages").get(1).path("occurredAt").asText());
        JsonNode meetings = outcome.coverage().path("meetings");
        assertEquals(1, meetings.path("transcribed").asInt(), outcome.coverage().toString());
        assertEquals(1, meetings.path("transcribedOnScreen").asInt());
        assertEquals(1, meetings.path("downloadBlocked").asInt());
    }

    @Test
    @DisplayName("Synchro : transcription lue à l'écran, téléchargement autorisé — pas de drapeau")
    void collect_does_not_flag_a_free_transcript() {
        PaperTeams teams = new PaperTeams().already("m1", TeamsSamples.MEETINGS_URL, "meetings.json");
        teams.browser.reachable(MEETING_CHAT);
        teams.browser.screen("", PaperScreen.of("transcription-libre.html"));
        Context context = new Context();

        collector(teams).collect(assignment("2026-09-01T00:00:00Z", null), context);

        JsonNode exchange = context.submitted.stream()
                .map(body -> body.path("batch").path("exchanges").get(0))
                .filter(node -> "TEAMS_MEETING".equals(node.path("source").asText()))
                .findFirst().orElseThrow();
        assertFalse(exchange.has("downloadBlocked"));
    }
}
