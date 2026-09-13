package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.ToolContext;
import fr.claudegateway.runner.ToolOutcome;

/**
 * F-100 / SF-100-01 — <b>la vérification guidée</b> : ce que le runner voit de la session Microsoft,
 * en compteurs et en états, et pourquoi une case reste vide.
 */
class RadarVerifyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode verify(TeamsTools tools) throws Exception {
        ToolOutcome outcome = tools.execute(RadarTools.VERIFY, MAPPER.createObjectNode(), ToolContext.none());
        assertTrue(outcome.ok(), outcome.content());
        return MAPPER.readTree(outcome.content());
    }

    @Test
    @DisplayName("Fil, réunion et transcription servis par Teams : les quatre cases sont cochées")
    void everythingSeen() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("c1", TeamsSamples.CONVERSATIONS_URL, "conversation-list.json")
                .already("m1", TeamsSamples.MEETINGS_URL, "meetings.json")
                .already("t1", TeamsSamples.TRANSCRIPT_URL, "transcript.json");

        JsonNode result = verify(teams.tools());

        JsonNode checks = result.path("checks");
        assertTrue(checks.path("session").path("ok").asBoolean());
        assertEquals("LINKED", checks.path("session").path("state").asText());
        assertTrue(checks.path("conversations").path("ok").asBoolean());
        assertTrue(checks.path("conversations").path("count").asInt() >= 3);
        assertTrue(checks.path("meetings").path("ok").asBoolean());
        assertTrue(checks.path("transcripts").path("ok").asBoolean());
        assertEquals("SEEN", checks.path("transcripts").path("reason").asText());
        assertTrue(result.path("complete").asBoolean());

        // Des compteurs et des états : ni titre, ni nom, ni adresse.
        for (String forbidden : List.of("Migration MFA", "Comité", "Paul Durand", "Francky", "19:",
                "teams.microsoft.com", "orgid")) {
            assertFalse(result.toString().contains(forbidden), "fuite « " + forbidden + " » : " + result);
        }
    }

    @Test
    @DisplayName("Transcription refusée (403) : ACCESS_DENIED, et le corps du refus n'est pas demandé")
    void transcriptDenied() throws Exception {
        PaperTeams teams = new PaperTeams().already("m1", TeamsSamples.MEETINGS_URL, "meetings.json");
        teams.browser.emitResponse("t403", TeamsSamples.TRANSCRIPT_URL, "{\"error\":\"Forbidden\"}", 403);

        JsonNode transcripts = verify(teams.tools()).path("checks").path("transcripts");

        assertFalse(transcripts.path("ok").asBoolean());
        assertEquals("ACCESS_DENIED", transcripts.path("reason").asText());
        assertEquals(1, teams.link.observer().denied(TeamsPayloadKind.MEETING_TRANSCRIPT));
    }

    @Test
    @DisplayName("Réunions vues sans transcription annoncée : DISABLED_OR_NOT_PRODUCED")
    void transcriptDisabled() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.emitResponse("m1", TeamsSamples.MEETINGS_URL, "{\"value\":[{\"id\":\"MTG-1\","
                + "\"subject\":\"Point\",\"startTime\":\"2026-09-10T09:00:00Z\",\"isTranscriptAvailable\":false}]}");

        JsonNode checks = verify(teams.tools()).path("checks");

        assertTrue(checks.path("meetings").path("ok").asBoolean());
        assertEquals("DISABLED_OR_NOT_PRODUCED", checks.path("transcripts").path("reason").asText());
        assertFalse(verify(teams.tools()).path("complete").asBoolean());
    }

    @Test
    @DisplayName("Rien encore servi : NOT_SEEN, avec le geste à faire")
    void nothingSeen() throws Exception {
        JsonNode checks = verify(new PaperTeams().tools()).path("checks");

        assertTrue(checks.path("session").path("ok").asBoolean());
        assertFalse(checks.path("conversations").path("ok").asBoolean());
        assertEquals("NOT_SEEN", checks.path("transcripts").path("reason").asText());
        assertTrue(checks.path("conversations").path("sentence").asText().contains("ouvrez un fil"));
    }

    @Test
    @DisplayName("Navigateur non relié : session ✗ avec l'état et le remède ; volet désactivé : ✗ nommé")
    void notLinkedOrDisabled() throws Exception {
        TeamsTools unlinked = new TeamsTools(new TeamsSession(9222, TeamsAdapters.current(), message -> { },
                (port, adapter, say) -> {
                    throw new BrowserLinkException(BrowserLinkException.NOT_SIGNED_IN, "Reconnectez-vous à Teams.");
                }), millis -> { });
        JsonNode session = verify(unlinked).path("checks").path("session");
        assertFalse(session.path("ok").asBoolean());
        assertEquals("NOT_SIGNED_IN", session.path("state").asText());
        assertEquals("Reconnectez-vous à Teams.", session.path("sentence").asText());

        JsonNode disabled = verify(TeamsTools.disabled("Volet coupé (--no-teams).")).path("checks");
        assertEquals("TEAMS_DISABLED", disabled.path("session").path("state").asText());
        assertFalse(disabled.path("transcripts").path("ok").asBoolean());
    }

    @Test
    @DisplayName("Les appels du Radar ne sont pas dans le catalogue de l'agent")
    void radarToolsAreNotAgentTools() {
        assertFalse(TeamsTools.CATALOG.contains(RadarTools.VERIFY));
    }
}
