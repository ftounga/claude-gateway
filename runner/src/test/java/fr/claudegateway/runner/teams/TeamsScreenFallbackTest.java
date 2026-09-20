package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.ToolOutcome;

/**
 * F-89 / SF-89-06 — <b>lire l'écran quand le réseau ne donne rien</b>.
 *
 * <p>Décision du PO du 2026-09-13 (cadrage F-87 §9 bis), sur le constat du relevé réel : le nouveau
 * Teams sert l'historique d'un fil depuis son cache local. Les DOM modèles de {@code teams/ecran/} sont
 * construits pour ces tests et restent <b>à confirmer sur poste réel</b> ; ils éprouvent la table de
 * sélecteurs, le refiltrage, le défilement, le recollage, les manques, la source et la règle de
 * non-recopie d'une transcription au téléchargement bloqué.</p>
 */
class TeamsScreenFallbackTest {

    private static final String MEETING_THREAD = "19:meeting_fabrique@thread.v2";

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode call(TeamsTools tools, String tool, ObjectNode input) throws Exception {
        ToolOutcome outcome = tools.execute(tool, input);
        assertTrue(outcome.ok(), outcome.errorMessage());
        return mapper.readTree(outcome.content());
    }

    private ObjectNode thread(String from) {
        return mapper.createObjectNode().put("conversation_id", PaperTeams.THREAD).put("from", from)
                .put("to", "2026-09-30T00:00:00Z");
    }

    private static List<String> texts(JsonNode messages) {
        List<String> out = new ArrayList<>();
        messages.forEach(message -> out.add(message.path("text").asText()));
        return out;
    }

    // ------------------------------------------------------------------ le fil

    @Test
    @DisplayName("Fil servi depuis le cache : lu à l'écran sur trois écrans, recollé sans doublon, dans l'ordre, vue remise")
    void a_cached_thread_is_read_on_screen() throws Exception {
        PaperTeams teams = new PaperTeams();
        PaperScreen screen = PaperScreen.of("fil-ecran-1.html", "fil-ecran-2.html", "fil-ecran-3.html");
        teams.browser.screen("", screen);

        String rendered = teams.tools().execute(TeamsTools.READ_CONVERSATION, thread("2026-09-01T00:00:00Z")).content();
        JsonNode json = mapper.readTree(rendered);

        assertEquals("ecran", json.path("source").asText());
        assertEquals(TeamsScreen.VERSION, json.path("screenVersion").asText());
        assertEquals(List.of("Bonjour à tous, on démarre la migration.", "Le plan de bascule est prêt.",
                "On valide le lot 3 demain ?", "Oui, je t'envoie le plan demain.", ""), texts(json.path("messages")));
        assertEquals("Paul Durand", json.path("messages").get(2).path("author").asText());
        assertEquals("2026-09-09T08:00:00Z", json.path("messages").get(0).path("sentAt").asText());
        assertTrue(json.path("window").path("reachedStart").asBoolean(), json.path("window").toString());
        assertTrue(json.path("text").asText().contains("Lu à l'écran"), json.path("text").asText());
        assertEquals(0, screen.current(), "la position de défilement est remise");
        assertTrue(json.path("viewport").asText().contains("à l'écran"));
        for (String secret : List.of("SECRET-BROUILLON", "SECRET-MOT-DE-PASSE", "SECRET-EN-COURS-D-EDITION")) {
            assertFalse(rendered.contains(secret), secret + " : un champ de saisie n'est jamais lu");
        }
    }

    @Test
    @DisplayName("La lecture s'arrête dès que la fenêtre demandée est couverte")
    void the_screen_reading_stops_when_the_window_is_covered() throws Exception {
        PaperTeams teams = new PaperTeams();
        PaperScreen screen = PaperScreen.of("fil-ecran-1.html", "fil-ecran-2.html", "fil-ecran-3.html");
        teams.browser.screen("", screen);

        JsonNode json = call(teams.tools(), TeamsTools.READ_CONVERSATION, thread("2026-09-10T17:00:00Z"));

        assertEquals(1, screen.restoredTo(), "deux écrans suffisaient : le troisième n'est jamais affiché");
        assertEquals(List.of("On valide le lot 3 demain ?", "Oui, je t'envoie le plan demain.", ""),
                texts(json.path("messages")), "le message du 10/09 à 16 h précède la fenêtre : il couvre, il n'est pas rendu");
    }

    @Test
    @DisplayName("Deux écrans sans recouvrement : un morceau a pu être sauté, et c'est dit")
    void screens_without_overlap_are_a_named_gap() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.screen("", PaperScreen.of("fil-ecran-1.html", "fil-lointain.html"));

        JsonNode json = call(teams.tools(), TeamsTools.READ_CONVERSATION, thread("2026-08-01T00:00:00Z"));

        assertTrue(json.path("gaps").toString().contains("sans recouvrement"), json.path("gaps").toString());
        assertFalse(json.path("window").path("reachedStart").asBoolean(),
                "une lecture trouée ne se dit pas complète");
    }

    @Test
    @DisplayName("Structure absente : « Teams a changé d'écran », zéro message, jamais un résultat inventé")
    void an_unknown_structure_is_a_named_gap() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.screen("", PaperScreen.of("structure-inconnue.html"));

        JsonNode json = call(teams.tools(), TeamsTools.READ_CONVERSATION, thread("2026-09-01T00:00:00Z"));

        assertEquals(0, json.path("messages").size());
        assertEquals("aucune", json.path("source").asText());
        assertTrue(json.path("gaps").toString().contains("SCREEN_CHANGED"), json.path("gaps").toString());
        assertTrue(json.path("text").asText().contains("Teams a changé d'écran"), json.path("text").asText());
    }

    @Test
    @DisplayName("Le réseau a répondu : l'écran n'est pas lu, et la source le dit")
    void the_network_wins_when_it_answers() throws Exception {
        PaperTeams teams = new PaperTeams().already("r1", PaperTeams.MESSAGES_URL, "conversation-messages.json");
        teams.browser.screen("", PaperScreen.of("fil-ecran-1.html"));

        JsonNode json = call(teams.tools(), TeamsTools.READ_CONVERSATION,
                mapper.createObjectNode().put("conversation_id", PaperTeams.THREAD));

        assertEquals("reseau", json.path("source").asText());
        assertTrue(json.path("messages").size() > 0);
        assertTrue(teams.browser.screenScripts().isEmpty(), "aucun script d'écran quand le réseau a servi");
    }

    // ------------------------------------------------------------------ listes

    @Test
    @DisplayName("Liste des conversations affichée : lue à l'écran quand le réseau n'a rien servi")
    void the_displayed_conversation_list_is_read() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.screen("", PaperScreen.of("conversations.html"));

        String rendered = teams.tools().execute(TeamsTools.FIND_CONVERSATIONS, mapper.createObjectNode()).content();
        JsonNode json = mapper.readTree(rendered);

        assertEquals("ecran", json.path("source").asText());
        assertEquals(2, json.path("conversations").size(), rendered);
        assertEquals("19:fabrique@thread.v2", json.path("conversations").get(0).path("id").asText());
        assertEquals("Migration IAM", json.path("conversations").get(0).path("topic").asText());
        assertFalse(rendered.contains("SECRET-RECHERCHE-EN-COURS"));
    }

    @Test
    @DisplayName("Mentions : le flux d'activité est ouvert, seules les mentions sont lues, la vue est remise")
    void mentions_are_read_on_the_activity_screen() throws Exception {
        PaperTeams teams = new PaperTeams();
        String before = teams.browser.route();
        teams.browser.screen("#/activity", PaperScreen.of("activite.html"));

        JsonNode json = call(teams.tools(), TeamsTools.MENTIONS,
                mapper.createObjectNode().put("from", "2026-09-01T00:00:00Z").put("to", "2026-09-30T00:00:00Z"));

        assertEquals("ecran", json.path("source").asText());
        assertEquals(2, json.path("mentions").size(), json.toString());
        assertEquals("Paul Durand", json.path("mentions").get(0).path("author").asText());
        assertEquals("Migration IAM", json.path("mentions").get(0).path("conversation").asText());
        assertTrue(teams.browser.navigations().contains("https://teams.microsoft.com/v2/#/activity"));
        assertEquals(before, teams.browser.route());
        assertFalse(json.toString().toLowerCase(Locale.ROOT).contains("réagi"));
    }

    // ------------------------------------------------------------------ SF-100-10 : la vue Chat avant de lire

    @Test
    @DisplayName("SF-100-10 : reachChatList navigue vers la vue Chat et attend la liste ; jamais chargée → best-effort borné")
    void reach_chat_list_navigates_then_waits_for_the_mid_nav_list() {
        // La liste « mid-nav » ne charge qu'après deux sondages de présence : reachChatList doit l'attendre.
        PaperTeams present = new PaperTeams();
        present.browser.listPresentAfter(2, PaperScreen.of("conversations-v2.html"));
        int[] polls = { 0 };
        TeamsScreenFallback loads = new TeamsScreenFallback(present.link, millis -> polls[0]++, null);

        TeamsScreenFallback.ChatView view = loads.reachChatList(TeamsScreenFallback.MAX_CHAT_LIST_POLLS);

        assertTrue(view.reached(), "la navigation vers la vue Chat a été tentée");
        assertTrue(view.listPresent(), "la liste a fini par charger, reachChatList l'a attendue");
        assertTrue(present.browser.navigations().contains(TeamsRoutes.CHAT), present.browser.navigations().toString());
        assertTrue(polls[0] >= 2, "poll d'attente via le Sleeper : " + polls[0]);

        // Jamais chargée : best-effort borné, pas de crash, la liste reste absente.
        PaperTeams never = new PaperTeams();
        TeamsScreenFallback nothing = new TeamsScreenFallback(never.link, millis -> { }, null);

        TeamsScreenFallback.ChatView timeout = nothing.reachChatList(TeamsScreenFallback.MAX_CHAT_LIST_POLLS);

        assertTrue(timeout.reached());
        assertFalse(timeout.listPresent(), "best-effort : la liste ne s'est jamais chargée");
        assertTrue(never.browser.navigations().contains(TeamsRoutes.CHAT));
    }

    // ------------------------------------------------------------------ transcription

    @Test
    @DisplayName("Transcription : panneau ouvert, répliques datées depuis le début de réunion, téléchargement bloqué → règle de non-recopie")
    void a_blocked_transcript_is_read_and_carries_the_rule() throws Exception {
        PaperTeams teams = new PaperTeams().already("m1", TeamsSamples.MEETINGS_URL, "meetings.json");
        teams.browser.reachable(MEETING_THREAD);
        teams.browser.screenOnClick("transcript-tab",
                PaperScreen.of("transcription-bloquee-1.html", "transcription-bloquee-2.html"));

        JsonNode json = call(teams.tools(), TeamsTools.MEETING_TRANSCRIPT,
                mapper.createObjectNode().put("meeting_id", "MTG-FABRIQUE-0001"));

        assertEquals("ecran", json.path("source").asText());
        assertEquals(3, json.path("cues").size(), json.toString());
        assertEquals("2026-09-10T09:01:05Z", json.path("cues").get(1).path("at").asText());
        assertEquals("1:02:03", json.path("cues").get(2).path("offset").asText());
        assertEquals("Francky Tounga", json.path("cues").get(2).path("speaker").asText());
        assertTrue(json.path("downloadBlocked").asBoolean());
        assertTrue(json.path("usage").asText().contains("ne recopie JAMAIS la transcription brute"), json.toString());
        assertTrue(json.path("text").asText().contains("signale ce blocage"), json.path("text").asText());
        assertTrue(teams.browser.clickedSelectors().contains("transcript-tab"));
    }

    @Test
    @DisplayName("Transcription : bouton de téléchargement absent → bloqué ; actif → pas de règle")
    void the_download_state_is_read_from_the_button() throws Exception {
        PaperTeams hidden = new PaperTeams().already("m1", TeamsSamples.MEETINGS_URL, "meetings.json");
        hidden.browser.screen("", PaperScreen.of("transcription-sans-bouton.html"));
        JsonNode blocked = call(hidden.tools(), TeamsTools.MEETING_TRANSCRIPT,
                mapper.createObjectNode().put("meeting_id", "MTG-FABRIQUE-0001"));
        assertTrue(blocked.path("downloadBlocked").asBoolean());

        PaperTeams free = new PaperTeams().already("m1", TeamsSamples.MEETINGS_URL, "meetings.json");
        free.browser.screen("", PaperScreen.of("transcription-libre.html"));
        JsonNode allowed = call(free.tools(), TeamsTools.MEETING_TRANSCRIPT,
                mapper.createObjectNode().put("meeting_id", "MTG-FABRIQUE-0001"));
        assertEquals(1, allowed.path("cues").size());
        assertFalse(allowed.path("downloadBlocked").asBoolean(true));
        assertFalse(allowed.has("usage"));
        assertFalse(allowed.path("text").asText().contains("ne recopie JAMAIS"));
    }

    @Test
    @DisplayName("Transcription sans panneau trouvable : manque nommé, pas d'état de téléchargement inventé")
    void a_missing_transcript_panel_is_named() throws Exception {
        PaperTeams teams = new PaperTeams().already("m1", TeamsSamples.MEETINGS_URL, "meetings.json");

        JsonNode json = call(teams.tools(), TeamsTools.MEETING_TRANSCRIPT,
                mapper.createObjectNode().put("meeting_id", "MTG-FABRIQUE-0001"));

        assertEquals(0, json.path("cues").size());
        assertFalse(json.has("downloadBlocked"));
        assertTrue(json.path("gaps").toString().contains("SCREEN_CHANGED"), json.path("gaps").toString());
    }

    // ------------------------------------------------------------------ les gardes

    @Test
    @DisplayName("Gardes : aucun script d'écran ne touche aux cookies, au stockage, aux caches ni à la valeur d'un champ")
    void screen_scripts_never_touch_cookies_storage_or_field_values() {
        List<String> scripts = new ArrayList<>();
        for (TeamsScreen.View view : List.of(TeamsScreen.MESSAGES, TeamsScreen.CONVERSATIONS, TeamsScreen.ACTIVITY,
                TeamsScreen.TRANSCRIPT)) {
            scripts.add(TeamsScreen.readScript(mapper, view));
            scripts.add(TeamsScreen.scrollScript(mapper, view, true));
            scripts.add(TeamsScreen.restoreScript(mapper, view, 12));
            scripts.add(TeamsScreen.positionScript(mapper, view));
        }
        scripts.add(TeamsScreen.downloadControlScript(mapper));
        for (String script : scripts) {
            String lower = script.toLowerCase(Locale.ROOT);
            for (String forbidden : List.of("cookie", "localstorage", "sessionstorage", "indexeddb", "caches",
                    ".value", "location.")) {
                assertFalse(lower.contains(forbidden), forbidden + " dans " + script);
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> TeamsScreen.Field.attribute(List.of("input"), "value"), "l'attribut value n'est pas lisible");
    }

    @Test
    @DisplayName("Refiltrage Java : clés hors table écartées, valeurs non textuelles écartées, longueur bornée, contrôles retirés")
    void java_refilters_what_the_script_returns() throws Exception {
        String huge = "x".repeat(TeamsScreen.MAX_VALUE_CHARS + 500);
        JsonNode value = mapper.readTree("{\"found\":true,\"atStart\":true,\"items\":[{\"author\":\"Paul\\u0007 Durand\","
                + "\"time\":\"2026-09-11T09:30:00Z\",\"text\":\"" + huge + "\",\"cookie\":\"SECRET\",\"id\":{\"x\":1}},"
                + "\"pas un objet\"]}");

        TeamsScreen.Reading reading = TeamsScreen.refilter(TeamsScreen.MESSAGES, value);

        assertTrue(reading.found());
        assertTrue(reading.atStart());
        assertEquals(1, reading.items().size());
        Map<String, String> item = reading.items().get(0);
        assertEquals("Paul Durand", item.get("author"));
        assertEquals(TeamsScreen.MAX_VALUE_CHARS, item.get("text").length());
        assertFalse(item.containsKey("cookie"));
        assertFalse(item.containsKey("id"));
        assertFalse(TeamsScreen.refilter(TeamsScreen.MESSAGES, mapper.readTree("{\"found\":false}")).found());
        assertFalse(TeamsScreen.refilter(TeamsScreen.MESSAGES, null).found());
    }

    @Test
    @DisplayName("Décalages de transcription affichés")
    void transcript_offsets_are_parsed() {
        assertEquals(0, TeamsScreen.offsetSeconds("0:00"));
        assertEquals(65, TeamsScreen.offsetSeconds("1:05"));
        assertEquals(3723, TeamsScreen.offsetSeconds("1:02:03"));
        assertEquals(-1, TeamsScreen.offsetSeconds("hier"));
        assertEquals(-1, TeamsScreen.offsetSeconds(null));
        assertNull(null);
    }
}
