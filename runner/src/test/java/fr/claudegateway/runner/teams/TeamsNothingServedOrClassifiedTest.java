package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.ToolOutcome;

/**
 * F-89 / SF-89-08 — <b>Teams relié, rien classé</b> : un zéro porte la panne qui l'explique, et son remède.
 *
 * <p>Constat du poste client (2026-09-13) : « rien d'observé depuis le rattachement » alors que Teams avait
 * servi du contenu ; l'agent a fait rouvrir cinq fois des écrans déjà ouverts. Deux pannes, deux remèdes :
 * {@code NOTHING_SERVED} (ouvrir l'écran) et {@code NOTHING_CLASSIFIED} (cliquer n'y changera rien). Et
 * l'inventaire complet des chemins non reconnus, pour les apprendre.</p>
 */
class TeamsNothingServedOrClassifiedTest {

    private static final String UNKNOWN_1 = "https://teams.microsoft.com/api/mcps/eu/contents?token=SECRET";
    private static final String UNKNOWN_2 =
            "https://teams.microsoft.com/api/chatsvc/fr/v1/threads/19:secretthread@thread.v2/consumptionhorizons";
    private static final String PROFILE_URL =
            "https://teams.microsoft.com/api/mt/emea/beta/users/8:orgid:00000000-0000-0000-0000-000000000009/profile";

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode call(TeamsTools tools, String tool, ObjectNode input) throws Exception {
        ToolOutcome outcome = tools.execute(tool, input);
        assertTrue(outcome.ok(), outcome.errorMessage());
        return mapper.readTree(outcome.content());
    }

    private ObjectNode wideWindow() {
        return mapper.createObjectNode().put("from", "2026-01-01T00:00:00Z").put("to", "2027-01-01T00:00:00Z");
    }

    private static JsonNode gap(JsonNode json, String kind) {
        for (JsonNode candidate : json.path("gaps")) {
            if (kind.equals(candidate.path("kind").asText())) {
                return candidate;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ la règle, seule

    @Test
    @DisplayName("La nature utile tranche : classée → inchangé ; non reconnu Microsoft → CLASSIFIED ; sinon SERVED")
    void the_useful_kind_decides() {
        ObservationDiagnostic nothing = ObservationDiagnostic.none();
        ObservationDiagnostic staticOnly = new ObservationDiagnostic(true, Map.of(), Map.of("SERVICE_WORKER", 20),
                Map.of(), 20, 0, 0, List.of(), 0, 0, 0);
        ObservationDiagnostic profilesAndUnknown = new ObservationDiagnostic(true, Map.of(), Map.of("TEAMS_TAB", 632),
                Map.of("PROFILE", 15, "CONVERSATION_LIST", 1), 400, 110, 106, List.of(), 0, 3, 40);

        assertEquals(TeamsGapKind.NOTHING_SERVED, nothing.nothingKind(TeamsTools.MEETING_KINDS));
        assertEquals(TeamsGapKind.NOTHING_SERVED, staticOnly.nothingKind(TeamsTools.MEETING_KINDS),
                "des statiques ne sont pas « du contenu arrivé » (D1)");
        assertEquals(TeamsGapKind.NOTHING_CLASSIFIED, profilesAndUnknown.nothingKind(TeamsTools.MEETING_KINDS),
                "15 profils classés ne disent rien des réunions (D2)");
        assertEquals(TeamsGapKind.NOTHING_OBSERVED, profilesAndUnknown.nothingKind(TeamsTools.CONVERSATION_KINDS),
                "une liste de conversations a été classée : servi, reconnu, rien ne correspond");
        assertTrue(profilesAndUnknown.detail(TeamsGapKind.NOTHING_CLASSIFIED).contains("n'y changera rien"));
        assertTrue(nothing.detail(TeamsGapKind.NOTHING_SERVED).contains("ouvrez l'écran voulu"));
    }

    // ------------------------------------------------------------------ les outils

    @Test
    @DisplayName("CA5 — réunions, aucun trafic : NOTHING_SERVED, et l'invitation à ouvrir l'écran")
    void no_traffic_is_nothing_served() throws Exception {
        PaperTeams teams = new PaperTeams();

        JsonNode json = call(teams.tools(), TeamsTools.FIND_MEETINGS, wideWindow());

        assertEquals(0, json.path("meetings").size());
        JsonNode served = gap(json, "NOTHING_SERVED");
        assertTrue(served != null, json.path("gaps").toString());
        assertTrue(json.path("text").asText().contains("Ouvrez l'écran voulu dans Teams, puis redemandez"),
                json.path("text").asText());
        assertEquals(null, gap(json, "NOTHING_OBSERVED"));
    }

    @Test
    @DisplayName("CA6 — réunions : 15 profils classés et des chemins inconnus, 0 réunion → NOTHING_CLASSIFIED")
    void recognized_profiles_do_not_hide_unrecognized_meetings() throws Exception {
        PaperTeams teams = new PaperTeams();
        for (int index = 0; index < 15; index++) {
            teams.browser.emitResponse("p" + index, PROFILE_URL, "{}");
        }
        teams.browser.emitResponse("u1", UNKNOWN_1, "{}");
        teams.browser.emitResponse("u2", UNKNOWN_2, "{}");

        JsonNode json = call(teams.tools(), TeamsTools.FIND_MEETINGS, wideWindow());

        assertEquals(0, json.path("meetings").size());
        JsonNode classified = gap(json, "NOTHING_CLASSIFIED");
        assertTrue(classified != null, json.path("gaps").toString());
        String text = json.path("text").asText();
        assertTrue(text.contains("n'a pas été reconnu"), text);
        assertTrue(text.contains("n'y changera rien"), text);
        assertFalse(text.contains("Ouvrez l'écran voulu"), "ne fait pas rouvrir un écran déjà ouvert : " + text);
        assertEquals(15, json.path("observation").path("classifiedByKind").path("PROFILE").asInt());
        assertTrue(json.path("observation").path("topUnknownPaths").size() > 0, "les chemins inconnus en diagnostic");
    }

    @Test
    @DisplayName("CA4 — transcription sur NOTHING_CLASSIFIED : plus de « Ouvrez la transcription dans Teams »")
    void a_transcript_zero_on_unrecognized_content_does_not_ask_to_reopen() throws Exception {
        PaperTeams teams = new PaperTeams().already("m1", TeamsSamples.MEETINGS_URL, "meetings.json");
        teams.browser.emitResponse("u1", UNKNOWN_1, "{}");

        JsonNode json = call(teams.tools(), TeamsTools.MEETING_TRANSCRIPT,
                mapper.createObjectNode().put("meeting_id", "MTG-FABRIQUE-0001"));

        assertEquals(0, json.path("cues").size());
        assertTrue(gap(json, "NOTHING_CLASSIFIED") != null, json.path("gaps").toString());
        assertFalse(json.path("text").asText().contains("Ouvrez la transcription dans Teams"),
                json.path("text").asText());
        assertTrue(json.has("observation"));
    }

    @Test
    @DisplayName("CA7 — NOTHING_CLASSIFIED n'empêche pas le repli : la liste affichée est lue à l'écran")
    void the_screen_fallback_still_reads_on_unrecognized_content() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.emitResponse("u1", UNKNOWN_1, "{}");
        teams.browser.screen("", PaperScreen.of("conversations.html"));

        JsonNode found = call(teams.tools(), TeamsTools.FIND_CONVERSATIONS, mapper.createObjectNode());
        JsonNode missed = call(teams.tools(), TeamsTools.FIND_CONVERSATIONS,
                mapper.createObjectNode().put("query", "introuvable"));

        assertEquals("ecran", found.path("source").asText());
        assertEquals(2, found.path("conversations").size(), found.toString());
        assertEquals(null, gap(found, "NOTHING_CLASSIFIED"));
        assertEquals("ecran", missed.path("source").asText());
        assertTrue(gap(missed, "NOTHING_OBSERVED") != null,
                "une liste lue à l'écran sans correspondance n'est pas une panne du réseau : " + missed.path("gaps"));
    }

    @Test
    @DisplayName("CA8 — teams_status : l'inventaire COMPLET des chemins non reconnus, trié, borné à 200, sans secret")
    void status_carries_the_full_inventory() throws Exception {
        PaperTeams teams = new PaperTeams();
        TeamsTools tools = teams.tools();
        call(tools, TeamsTools.STATUS, mapper.createObjectNode());
        teams.browser.emitAttached("SW", "service_worker", "https://teams.microsoft.com/v2/sw.js");
        for (int index = 0; index < 205; index++) {
            teams.browser.emitResponse("r" + index, "https://teams.microsoft.com/api/inconnu/p" + index + "?token=SECRET",
                    "{}");
        }
        teams.browser.emitResponse("x1", UNKNOWN_2, "{}");
        teams.browser.emitSessionResponse("SW", "x2", UNKNOWN_2, "{}");
        teams.browser.emitResponse("x3", UNKNOWN_2, "{}");
        teams.browser.emitResponse("t1", "https://contoso-my.sharepoint.com/personal/jean_contoso_com/_api/web", "{}");

        JsonNode observation = call(tools, TeamsTools.STATUS, mapper.createObjectNode())
                .path("diagnostic").path("observation");

        JsonNode inventory = observation.path("unknownPaths");
        assertEquals(ObservationDiagnostic.INVENTORY_PATHS, inventory.size());
        assertEquals(7, observation.path("unknownPathsNotListed").asInt(), "207 chemins distincts, 200 listés");
        JsonNode first = inventory.get(0);
        assertEquals("teams.microsoft.com", first.path("host").asText());
        assertEquals("/api/chatsvc/fr/v1/threads/{id}/consumptionhorizons", first.path("path").asText());
        assertEquals(3, first.path("count").asInt());
        assertEquals(List.of("TEAMS_TAB", "SERVICE_WORKER"),
                mapper.convertValue(first.path("origins"), List.class));
        assertEquals("application/json", first.path("mimeTypes").get(0).asText());
        assertEquals("teams.microsoft.com", inventory.get(1).path("host").asText());
        assertEquals(ObservationDiagnostic.TOP_PATHS, observation.path("topUnknownPaths").size());
        for (String secret : List.of("contoso", "jean_", "secretthread", "SECRET", "?")) {
            assertFalse(observation.toString().contains(secret), secret);
        }
    }
}
